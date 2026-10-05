-- A completion lease is independent of the 24-hour lifetime of signed upload sessions.
-- Fifteen minutes exceeds the Edge worker lifetime, leaving margin for a slow 50 MB completion.
alter table public.storage_uploads add column completing_at timestamptz;

-- Give pre-migration claims a full recovery window rather than expiring an active worker.
update public.storage_uploads set completing_at = now() where state = 'completing';

create or replace function public.storage_claim_completion(p_upload_id uuid, p_user_id uuid)
returns timestamptz
language plpgsql
security definer
set search_path = ''
as $$
declare
  claim_time timestamptz;
begin
  update public.storage_uploads
    set state = 'completing', completing_at = clock_timestamp(),
      expires_at = greatest(expires_at, now() + interval '24 hours')
    where id = p_upload_id and user_id = p_user_id
      and (
        state = 'pending'
        or (state = 'completing' and coalesce(completing_at, created_at) < now() - interval '15 minutes')
      )
    returning completing_at into claim_time;
  return claim_time;
end;
$$;

create or replace function public.storage_reserve_upload(
  p_user_id uuid,
  p_object_key text,
  p_content_hash text,
  p_content_type text,
  p_size_bytes bigint,
  p_part_checksums jsonb,
  p_expires_at timestamptz
) returns table (
  upload_id uuid,
  created boolean,
  expires_at timestamptz,
  provider_upload_id text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  available bigint;
  reserved_id uuid;
begin
  perform pg_advisory_xact_lock(hashtextextended(p_user_id::text || ':storage-quota', 0));

  -- Retain the handle: S3 may have completed before the worker lost its response. The next
  -- completion checks HEAD before sending CompleteMultipartUpload again.
  update public.storage_uploads as stale
    set state = 'pending', completing_at = null,
      expires_at = greatest(stale.expires_at, p_expires_at)
    where stale.user_id = p_user_id
      and stale.object_key = p_object_key
      and stale.state = 'completing'
      and coalesce(stale.completing_at, stale.created_at) < now() - interval '15 minutes';

  update public.storage_uploads as stale
    set state = 'failed'
    where stale.user_id = p_user_id
      and stale.object_key = p_object_key
      and stale.state = 'pending'
      and stale.expires_at < now();

  return query
    select existing.id, false, existing.expires_at, existing.provider_upload_id
    from public.storage_uploads existing
    where existing.user_id = p_user_id
      and existing.object_key = p_object_key
      and existing.state = 'pending'
      and existing.size_bytes = p_size_bytes
      and existing.content_type = p_content_type
      and existing.part_checksums = p_part_checksums
    limit 1;
  if found then return; end if;

  if exists (
    select 1 from public.storage_uploads existing
    where existing.user_id = p_user_id
      and existing.object_key = p_object_key
      and existing.state in ('completing', 'reaping')
  ) then
    raise exception using errcode = 'P0001', message = 'upload_in_progress';
  end if;
  if exists (
    select 1 from public.storage_uploads existing
    where existing.user_id = p_user_id
      and existing.object_key = p_object_key
      and existing.state in ('pending', 'ready')
  ) then
    raise exception using errcode = 'P0001', message = 'object_already_exists';
  end if;

  available := public.storage_remaining_quota(p_user_id);
  if p_size_bytes > available then
    raise exception using errcode = 'P0001', message = 'quota_exceeded', detail = available::text;
  end if;

  insert into public.storage_uploads (
    user_id, object_key, content_hash, content_type, size_bytes, part_checksums, expires_at
  ) values (
    p_user_id, p_object_key, p_content_hash, p_content_type, p_size_bytes, p_part_checksums, p_expires_at
  ) returning id into reserved_id;
  return query select reserved_id, true, p_expires_at, null::text;
end;
$$;

create or replace function public.storage_claim_expired_uploads(p_limit integer default 100)
returns table (id uuid, provider_upload_id text, object_key text)
language plpgsql
security definer
set search_path = ''
as $$
begin
  return query
    with claimed as (
      select candidate.id
      from public.storage_uploads candidate
      where (
          candidate.state = 'pending' and candidate.expires_at < now()
        ) or (
          candidate.state = 'completing' and candidate.expires_at < now()
          and coalesce(candidate.completing_at, candidate.created_at) < now() - interval '15 minutes'
        ) or (
          candidate.state = 'reaping' and candidate.reaping_at < now() - interval '15 minutes'
        )
      order by candidate.expires_at
      for update skip locked
      limit least(greatest(p_limit, 1), 500)
    )
    update public.storage_uploads upload
      set state = 'reaping', reaping_at = now()
      from claimed
      where upload.id = claimed.id
      returning upload.id, upload.provider_upload_id, upload.object_key;
end;
$$;

drop function public.storage_finalize_upload(uuid, uuid);
create function public.storage_finalize_upload(
  p_upload_id uuid, p_user_id uuid, p_completing_at timestamptz
) returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  finalized public.storage_uploads;
begin
  update public.storage_uploads
    set state = 'ready', completed_at = now(), completing_at = null
    where id = p_upload_id and user_id = p_user_id and state = 'completing'
      and completing_at = p_completing_at
    returning * into finalized;
  if not found then return false; end if;

  insert into public.thumbnail_generation_queue (upload_id, user_id, object_key)
    values (finalized.id, finalized.user_id, finalized.object_key)
    on conflict (upload_id) do nothing;
  return true;
end;
$$;

revoke all on function public.storage_claim_completion(uuid, uuid) from public, anon, authenticated;
revoke all on function public.storage_finalize_upload(uuid, uuid, timestamptz) from public, anon, authenticated;
grant execute on function public.storage_claim_completion(uuid, uuid) to service_role;
grant execute on function public.storage_finalize_upload(uuid, uuid, timestamptz) to service_role;
