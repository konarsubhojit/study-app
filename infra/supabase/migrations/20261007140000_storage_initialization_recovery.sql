-- A worker can stop after reserving quota but before persisting the provider upload handle.
-- Bound that initialization lease independently of the 24-hour multipart upload lifetime.
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
      and (
        stale.expires_at < now()
        or (stale.provider_upload_id is null and stale.created_at < now() - interval '15 minutes')
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
