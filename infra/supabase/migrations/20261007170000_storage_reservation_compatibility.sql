-- Incompatible pending metadata must not hold the content-addressed key indefinitely.
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

  -- Preserve recovery of a provider completion whose response was lost.
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
      and (
        stale.expires_at < now()
        or (stale.provider_upload_id is null and stale.created_at < now() - interval '15 minutes')
        or stale.size_bytes <> p_size_bytes
        or stale.content_type <> p_content_type
        or stale.part_checksums <> p_part_checksums
        or jsonb_array_length(stale.part_checksums) <> ceil(stale.size_bytes::numeric / 8388608)
      );

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

revoke all on function public.storage_reserve_upload(uuid, text, text, text, bigint, jsonb, timestamptz)
  from public, anon, authenticated;
grant execute on function public.storage_reserve_upload(uuid, text, text, text, bigint, jsonb, timestamptz)
  to service_role;

-- Fence completion against replacement between reading the row and acquiring its claim.
-- Keep the two-argument overload for older function deployments during rollout.
create or replace function public.storage_claim_completion(
  p_upload_id uuid,
  p_user_id uuid,
  p_provider_upload_id text
) returns timestamptz
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
      and provider_upload_id = p_provider_upload_id
      and (
        state = 'pending'
        or (state = 'completing' and coalesce(completing_at, created_at) < now() - interval '15 minutes')
      )
    returning completing_at into claim_time;
  return claim_time;
end;
$$;

revoke all on function public.storage_claim_completion(uuid, uuid, text) from public, anon, authenticated;
grant execute on function public.storage_claim_completion(uuid, uuid, text) to service_role;
