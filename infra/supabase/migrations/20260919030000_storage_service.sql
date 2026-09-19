-- Durable state and atomic guardrails for the presigned URL service (issue #56).
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'materials',
  'materials',
  false,
  52428800,
  array[
    'application/pdf',
    'image/gif',
    'image/jpeg',
    'image/png',
    'image/webp',
    'text/plain',
    'video/mp4'
  ]
)
on conflict (id) do update set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

create table public.storage_accounts (
  user_id uuid primary key references public.profiles (id) on delete cascade,
  quota_bytes bigint not null default 1073741824 check (quota_bytes > 0)
);

create table public.storage_uploads (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles (id) on delete cascade,
  object_key text not null,
  provider_upload_id text,
  content_hash text not null check (content_hash ~ '^[0-9a-f]{64}$'),
  content_type text not null,
  size_bytes bigint not null check (size_bytes > 0 and size_bytes <= 52428800),
  part_checksums jsonb not null,
  state text not null default 'pending'
    check (state in ('pending', 'completing', 'ready', 'failed', 'reaping', 'deleted')),
  expires_at timestamptz not null,
  reaping_at timestamptz,
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  constraint storage_uploads_key_scoped_to_owner
    check (object_key = (user_id::text || '/' || content_hash)),
  constraint storage_uploads_part_checksums_array check (jsonb_typeof(part_checksums) = 'array')
);

create index storage_uploads_user_state_idx on public.storage_uploads (user_id, state);
create index storage_uploads_expired_idx on public.storage_uploads (expires_at)
  where state in ('pending', 'completing');
create unique index storage_uploads_active_key_idx on public.storage_uploads (object_key)
  where state in ('pending', 'completing', 'ready', 'reaping');

create table public.storage_url_audit (
  id bigint generated always as identity primary key,
  user_id uuid not null references public.profiles (id) on delete cascade,
  operation text not null check (operation in ('upload_part', 'download')),
  object_key text not null,
  upload_id uuid references public.storage_uploads (id) on delete set null,
  expires_at timestamptz not null,
  issued_at timestamptz not null default now()
);

create index storage_url_audit_user_issued_idx on public.storage_url_audit (user_id, issued_at);

create table public.storage_rate_events (
  id bigint generated always as identity primary key,
  user_id uuid not null references public.profiles (id) on delete cascade,
  operation text not null,
  occurred_at timestamptz not null default now()
);

create index storage_rate_events_user_operation_time_idx
  on public.storage_rate_events (user_id, operation, occurred_at);

create table public.thumbnail_generation_queue (
  upload_id uuid primary key references public.storage_uploads (id) on delete cascade,
  user_id uuid not null references public.profiles (id) on delete cascade,
  object_key text not null,
  state text not null default 'pending' check (state in ('pending', 'processing', 'complete', 'failed')),
  created_at timestamptz not null default now()
);

alter table public.storage_accounts enable row level security;
alter table public.storage_uploads enable row level security;
alter table public.storage_url_audit enable row level security;
alter table public.storage_rate_events enable row level security;
alter table public.thumbnail_generation_queue enable row level security;

-- These are service internals. The Edge Function uses service_role and exposes only safe response
-- fields; clients cannot query or mutate provider upload handles or abuse counters directly.
revoke all on public.storage_accounts from anon, authenticated;
revoke all on public.storage_uploads from anon, authenticated;
revoke all on public.storage_url_audit from anon, authenticated;
revoke all on public.storage_rate_events from anon, authenticated;
revoke all on public.thumbnail_generation_queue from anon, authenticated;

create or replace function public.storage_consume_rate_limit(
  p_user_id uuid,
  p_operation text,
  p_limit integer,
  p_window_seconds integer
) returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  recent_count integer;
begin
  if p_limit <= 0 or p_window_seconds <= 0 then
    raise exception 'invalid rate-limit configuration';
  end if;

  perform pg_advisory_xact_lock(hashtextextended(p_user_id::text || ':' || p_operation, 0));
  delete from public.storage_rate_events
    where occurred_at < now() - make_interval(secs => p_window_seconds);

  select count(*) into recent_count
    from public.storage_rate_events
    where user_id = p_user_id
      and operation = p_operation
      and occurred_at >= now() - make_interval(secs => p_window_seconds);

  if recent_count >= p_limit then
    raise exception using errcode = 'P0001', message = 'rate_limited';
  end if;

  insert into public.storage_rate_events (user_id, operation)
    values (p_user_id, p_operation);
end;
$$;

create or replace function public.storage_remaining_quota(p_user_id uuid)
returns bigint
language sql
stable
security definer
set search_path = ''
as $$
  select greatest(
    coalesce((select quota_bytes from public.storage_accounts where user_id = p_user_id), 1073741824)
      - coalesce((
          select sum(size_bytes)
          from public.storage_uploads
          where user_id = p_user_id and state in ('pending', 'completing', 'ready')
        ), 0),
    0
  );
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
  if found then
    return;
  end if;

  if exists (
    select 1 from public.storage_uploads existing
    where existing.user_id = p_user_id
      and existing.object_key = p_object_key
      and existing.state in ('completing', 'ready', 'reaping')
  ) then
    raise exception using errcode = 'P0001', message = 'object_already_exists';
  end if;

  available := public.storage_remaining_quota(p_user_id);
  if p_size_bytes > available then
    raise exception using errcode = 'P0001', message = 'quota_exceeded',
      detail = available::text;
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
          candidate.state in ('pending', 'completing')
          and candidate.expires_at < now()
        ) or (
          candidate.state = 'reaping'
          and candidate.reaping_at < now() - interval '15 minutes'
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

create or replace function public.storage_finalize_upload(p_upload_id uuid, p_user_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  finalized public.storage_uploads;
begin
  update public.storage_uploads
    set state = 'ready', completed_at = now()
    where id = p_upload_id and user_id = p_user_id and state = 'completing'
    returning * into finalized;
  if not found then
    return false;
  end if;

  insert into public.thumbnail_generation_queue (upload_id, user_id, object_key)
    values (finalized.id, finalized.user_id, finalized.object_key)
    on conflict (upload_id) do nothing;
  return true;
end;
$$;

revoke all on function public.storage_consume_rate_limit(uuid, text, integer, integer)
  from public, anon, authenticated;
revoke all on function public.storage_remaining_quota(uuid) from public, anon, authenticated;
revoke all on function public.storage_reserve_upload(uuid, text, text, text, bigint, jsonb, timestamptz)
  from public, anon, authenticated;
revoke all on function public.storage_claim_expired_uploads(integer) from public, anon, authenticated;
revoke all on function public.storage_finalize_upload(uuid, uuid) from public, anon, authenticated;
grant execute on function public.storage_consume_rate_limit(uuid, text, integer, integer) to service_role;
grant execute on function public.storage_remaining_quota(uuid) to service_role;
grant execute on function public.storage_reserve_upload(uuid, text, text, text, bigint, jsonb, timestamptz)
  to service_role;
grant execute on function public.storage_claim_expired_uploads(integer) to service_role;
grant execute on function public.storage_finalize_upload(uuid, uuid) to service_role;
