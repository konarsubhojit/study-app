-- Server half of task and material sync (ADR 0018): the generic record stream behind
-- /v1/sync/records.
--
-- A record is one replicated aggregate — a task with its checklist and reminders, or a catalogued
-- material's metadata — stored as the client's versioned JSON payload. The server does not
-- interpret the payload: conflicts are resolved on the envelope alone (updatedAt, deviceId,
-- deleted), by the same rule as sessions, so the client can evolve the payload with its export
-- format without a server migration. The relational study_tasks and materials tables stay as they
-- are; they serve the older list endpoints and are not written by sync.
--
-- The stream has its own per-user change clock rather than sharing the session one: the two
-- cursors are independent, and a device that has read every session must still be able to read
-- every record from the start (after an account change, for example).

-- ---------------------------------------------------------------------------------------------
-- Per-user change clock.
-- ---------------------------------------------------------------------------------------------
create table public.sync_record_clocks (
  user_id uuid primary key references public.profiles (id) on delete cascade,
  last_change_seq bigint not null check (last_change_seq >= 0)
);

comment on table public.sync_record_clocks is
  'Last sync_records.change_seq issued per user. Service-role only; never reused.';

alter table public.sync_record_clocks enable row level security;
revoke all on public.sync_record_clocks from anon, authenticated;

-- ---------------------------------------------------------------------------------------------
-- The records.
-- ---------------------------------------------------------------------------------------------
create table public.sync_records (
  user_id uuid not null references public.profiles (id) on delete cascade,
  entity_type text not null check (entity_type in ('task', 'material')),
  entity_id text not null check (entity_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
  device_id text not null check (btrim(device_id) <> '' and length(device_id) <= 128),
  sync_updated_at timestamptz not null,
  deleted boolean not null default false,
  schema_version integer not null check (schema_version >= 1),
  payload jsonb not null check (jsonb_typeof(payload) = 'object' and pg_column_size(payload) <= 65536),
  change_seq bigint not null,
  created_at timestamptz not null default now(),
  primary key (user_id, entity_type, entity_id)
);

comment on table public.sync_records is
  'Replicated tasks and materials (ADR 0018), one row per aggregate, keyed per owner so two '
  'accounts can never collide on an id. Deleted with the account by cascade.';
comment on column public.sync_records.sync_updated_at is
  'The client''s updatedAt: the last-writer-wins term, compared exactly as LastWriteWins.wins does.';
comment on column public.sync_records.change_seq is
  'Per-user commit-ordered change position; the delta cursor of GET /v1/sync/records.';

create unique index sync_records_user_id_change_seq_idx on public.sync_records (user_id, change_seq);

alter table public.sync_records enable row level security;

create policy "sync_records_select_own" on public.sync_records
  for select using (user_id = auth.uid());

-- Writes go only through sync_push_records under the service role; there is no insert, update or
-- delete policy for clients.

-- Same scheme as stamp_study_session_change_seq: the upsert holds the owner's clock row until
-- commit, which serialises one user's writers and makes the issued order the commit order.
create function public.stamp_sync_record_change_seq()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.sync_record_clocks as clock (user_id, last_change_seq)
    values (new.user_id, 1)
    on conflict (user_id) do update set last_change_seq = clock.last_change_seq + 1
    returning clock.last_change_seq into new.change_seq;
  return new;
end;
$$;

create trigger stamp_sync_records_change_seq
  before insert or update on public.sync_records
  for each row execute function public.stamp_sync_record_change_seq();

-- ---------------------------------------------------------------------------------------------
-- GET /v1/sync/records. The owner predicate is part of the function, as for sessions.
-- ---------------------------------------------------------------------------------------------
create function public.sync_pull_records(p_user_id uuid, p_after bigint, p_limit integer)
returns table (change_seq bigint, record jsonb)
language sql
stable
set search_path = public
as $$
  select
    r.change_seq,
    jsonb_build_object(
      'entityType', r.entity_type,
      'id', r.entity_id,
      'deviceId', r.device_id,
      'updatedAt', public.sync_timestamp(r.sync_updated_at),
      'deleted', r.deleted,
      'schemaVersion', r.schema_version,
      'payload', r.payload
    )
  from public.sync_records r
  where r.user_id = p_user_id
    and r.change_seq > p_after
  order by r.change_seq
  limit p_limit
$$;

-- ---------------------------------------------------------------------------------------------
-- POST /v1/sync/records. Each change is resolved on its own by the session rule
-- (LastWriteWins.wins on the device):
--
--   1. a different updatedAt: the later one wins;
--   2. an exact tie: the greater device_id under the "C" collation (code-point order);
--   3. same instant, same device: a tombstone beats a live row.
--
-- The winner replaces the whole record. A change is accepted when it won or is an exact replay of
-- the stored envelope, and rejected when the stored record beats it; the next delta delivers it.
-- ---------------------------------------------------------------------------------------------
create function public.sync_push_records(p_user_id uuid, p_changes jsonb)
returns jsonb
language plpgsql
set search_path = public
as $$
declare
  change jsonb;
  existing public.sync_records%rowtype;
  incoming_type text;
  incoming_id text;
  incoming_updated timestamptz;
  incoming_device text;
  incoming_deleted boolean;
  wins boolean;
  replay boolean;
  accepted text[] := '{}';
  rejected text[] := '{}';
begin
  for change in select value from jsonb_array_elements(p_changes) loop
    incoming_type := change->>'entityType';
    incoming_id := change->>'id';
    incoming_updated := (change->>'updatedAt')::timestamptz;
    incoming_device := change->>'deviceId';
    incoming_deleted := coalesce((change->>'deleted')::boolean, false);

    select * into existing from public.sync_records
      where user_id = p_user_id and entity_type = incoming_type and entity_id = incoming_id
      for update;

    if not found then
      insert into public.sync_records (
        user_id, entity_type, entity_id, device_id, sync_updated_at, deleted, schema_version, payload
      ) values (
        p_user_id, incoming_type, incoming_id, incoming_device, incoming_updated, incoming_deleted,
        (change->>'schemaVersion')::integer, change->'payload'
      );
      accepted := accepted || incoming_id;
      continue;
    end if;

    wins := case
      when incoming_updated <> existing.sync_updated_at then incoming_updated > existing.sync_updated_at
      when incoming_device collate "C" <> existing.device_id collate "C"
        then incoming_device collate "C" > existing.device_id collate "C"
      else incoming_deleted and not existing.deleted
    end;
    replay := not wins
      and incoming_updated = existing.sync_updated_at
      and incoming_device collate "C" = existing.device_id collate "C"
      and incoming_deleted = existing.deleted;

    if wins then
      update public.sync_records set
        device_id = incoming_device,
        sync_updated_at = incoming_updated,
        deleted = incoming_deleted,
        schema_version = (change->>'schemaVersion')::integer,
        payload = change->'payload'
      where user_id = p_user_id and entity_type = incoming_type and entity_id = incoming_id;
    end if;

    if wins or replay then
      accepted := accepted || incoming_id;
    else
      rejected := rejected || incoming_id;
    end if;
  end loop;

  return jsonb_build_object('acceptedIds', to_jsonb(accepted), 'rejectedIds', to_jsonb(rejected));
end;
$$;

revoke all on function public.sync_pull_records(uuid, bigint, integer) from public, anon, authenticated;
revoke all on function public.sync_push_records(uuid, jsonb) from public, anon, authenticated;
revoke all on function public.stamp_sync_record_change_seq() from public, anon, authenticated;
grant execute on function public.sync_pull_records(uuid, bigint, integer) to service_role;
grant execute on function public.sync_push_records(uuid, jsonb) to service_role;

-- ---------------------------------------------------------------------------------------------
-- Sessions keep their task link. Tasks now replicate as records, not as study_tasks rows, so the
-- foreign key and the existence check in sync_push_study_sessions would drop every session's
-- taskId on the way through. The link is kept as the owner's opaque reference instead, exactly as
-- the device stores it; it can only ever resolve within the owner's own records.
-- ---------------------------------------------------------------------------------------------
alter table public.study_sessions drop constraint study_sessions_task_id_fkey;

comment on column public.study_sessions.task_id is
  'The owning device''s task id, kept verbatim (ADR 0018): tasks replicate through sync_records, '
  'not study_tasks, so this is not a foreign key.';

create or replace function public.sync_push_study_sessions(p_user_id uuid, p_changes jsonb)
returns jsonb
language plpgsql
set search_path = public
as $$
declare
  change jsonb;
  existing public.study_sessions%rowtype;
  incoming_id uuid;
  incoming_updated timestamptz;
  incoming_device text;
  incoming_deleted boolean;
  incoming_subject uuid;
  incoming_task uuid;
  wins boolean;
  replay boolean;
  inserted integer;
  accepted text[] := '{}';
  rejected text[] := '{}';
begin
  for change in select value from jsonb_array_elements(p_changes) loop
    incoming_id := (change->>'id')::uuid;
    incoming_updated := (change->>'updatedAt')::timestamptz;
    incoming_device := change->>'deviceId';
    incoming_deleted := coalesce((change->>'deleted')::boolean, false);

    select * into existing from public.study_sessions where id = incoming_id for update;

    if found and existing.user_id <> p_user_id then
      rejected := rejected || incoming_id::text;
      continue;
    end if;

    -- Subjects do not sync, so a subject reference is kept only when it resolves to the caller's
    -- own row. A task reference is kept whenever it is well-formed (ADR 0018).
    incoming_subject := null;
    if coalesce(change->>'subjectId', '') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
      select id into incoming_subject from public.subjects
        where id = (change->>'subjectId')::uuid and user_id = p_user_id;
    end if;
    incoming_task := null;
    if coalesce(change->>'taskId', '') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
      incoming_task := (change->>'taskId')::uuid;
    end if;

    if existing.id is null then
      wins := true;
      replay := false;
    else
      wins := case
        when incoming_updated <> existing.sync_updated_at then incoming_updated > existing.sync_updated_at
        when incoming_device collate "C" <> existing.device_id collate "C"
          then incoming_device collate "C" > existing.device_id collate "C"
        else incoming_deleted and existing.deleted_at is null
      end;
      replay := not wins
        and incoming_updated = existing.sync_updated_at
        and incoming_device = existing.device_id
        and incoming_deleted = (existing.deleted_at is not null);
    end if;

    if existing.id is null then
      insert into public.study_sessions (
        id, user_id, subject_id, task_id, note, started_at, ended_at, counted_seconds,
        unverified_seconds, device_id, sync_updated_at, deleted_at, manual_override
      ) values (
        incoming_id, p_user_id, incoming_subject, incoming_task, change->>'note',
        (change->>'startedAt')::timestamptz, (change->>'endedAt')::timestamptz,
        (change->>'countedMillis')::numeric / 1000, (change->>'unverifiedMillis')::numeric / 1000,
        incoming_device, incoming_updated,
        case when incoming_deleted then incoming_updated end,
        coalesce((change->>'manualOverride')::boolean, false)
      );
    elsif wins then
      update public.study_sessions set
        subject_id = incoming_subject,
        task_id = incoming_task,
        note = change->>'note',
        started_at = (change->>'startedAt')::timestamptz,
        ended_at = (change->>'endedAt')::timestamptz,
        counted_seconds = (change->>'countedMillis')::numeric / 1000,
        unverified_seconds = (change->>'unverifiedMillis')::numeric / 1000,
        device_id = incoming_device,
        sync_updated_at = incoming_updated,
        deleted_at = case when incoming_deleted then coalesce(existing.deleted_at, incoming_updated) end,
        manual_override = coalesce((change->>'manualOverride')::boolean, false)
      where id = incoming_id;
    end if;

    insert into public.study_session_events (id, session_id, type, sequence, wall_clock)
      select e->>'id', incoming_id, e->>'type', (e->>'sequence')::bigint, (e->>'wallClock')::timestamptz
      from jsonb_array_elements(coalesce(change->'events', '[]'::jsonb)) as e
      on conflict (session_id, id) do nothing;
    get diagnostics inserted = row_count;

    if inserted > 0 and existing.id is not null and not wins then
      update public.study_sessions set change_seq = change_seq where id = incoming_id;
    end if;

    if wins or replay then
      accepted := accepted || incoming_id::text;
    else
      rejected := rejected || incoming_id::text;
    end if;
  end loop;

  return jsonb_build_object('acceptedIds', to_jsonb(accepted), 'rejectedIds', to_jsonb(rejected));
end;
$$;

revoke all on function public.sync_push_study_sessions(uuid, jsonb) from public, anon, authenticated;
grant execute on function public.sync_push_study_sessions(uuid, jsonb) to service_role;

-- ---------------------------------------------------------------------------------------------
-- Telemetry for the new routes (the operation column is a closed vocabulary).
-- ---------------------------------------------------------------------------------------------
alter table public.backend_observability_events
  drop constraint backend_observability_events_operation_check;
alter table public.backend_observability_events
  add constraint backend_observability_events_operation_check
  check (operation in (
    'initUpload', 'completeUpload', 'getDownloadUrl', 'delete', 'reapOrphans',
    'beginSignIn', 'signIn', 'refreshTokens', 'listSubjects', 'listTasks',
    'deleteAccount', 'pullSessionChanges', 'pushSessionChanges',
    'beginPasskeyRegistration', 'completePasskeyRegistration',
    'pullRecordChanges', 'pushRecordChanges', 'unknown'
  ));
