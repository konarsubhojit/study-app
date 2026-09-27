-- Server half of offline-first session sync (ADR 0012), with stopped-session event replication
-- as narrowed by ADR 0017.
--
-- Correction to 20260916090300_study_sessions.sql: its header says the session event log "never
-- leaves the device (ADR 0003)". That is stale. ADR 0012 made stopped sessions' logs replicate by
-- union on event id, and ADR 0017 settled which fields replicate. Migrations are append-only, so
-- the correction lives here and in the table comments below rather than in that file.
--
-- Three columns are added to study_sessions because the existing ones cannot serve the protocol:
--
-- * `sync_updated_at` is the client's `updatedAt`, the primary last-writer-wins term. The existing
--   `updated_at` is stamped by `set_updated_at()` with the server clock on every write, so it can
--   never equal the value both devices compare, and a server that resolved conflicts on it would
--   disagree with `SessionSyncMerge.wins` and make devices diverge.
-- * `change_seq` is a per-user, strictly increasing position assigned under a per-user row lock,
--   so it is also commit order. The delta cursor is built on it rather than on any timestamp:
--   ordering by the client's `updatedAt` would skip an offline device's late push of an older
--   edit, and ordering by `now()` can commit out of order across concurrent transactions. Both are
--   gaps a cursor can never recover.
-- * `task_id` and `manual_override` carry the remaining `SyncSession` fields.
--
-- Only stopped sessions are ever stored (ADR 0012): there is still no status column, because a
-- row here is by definition STOPPED, and the API rejects any other status before it reaches SQL.

-- ---------------------------------------------------------------------------------------------
-- Per-user change clock.
-- ---------------------------------------------------------------------------------------------
create table public.study_session_sync_clocks (
  user_id uuid primary key references public.profiles (id) on delete cascade,
  last_change_seq bigint not null check (last_change_seq >= 0)
);

comment on table public.study_session_sync_clocks is
  'Last study_sessions.change_seq issued per user. Service-role only; never reused, even after a '
  'hard delete, so a delta cursor can never skip a later write that happens to reuse a number.';

alter table public.study_session_sync_clocks enable row level security;
revoke all on public.study_session_sync_clocks from anon, authenticated;

-- ---------------------------------------------------------------------------------------------
-- study_sessions: sync columns.
-- ---------------------------------------------------------------------------------------------
alter table public.study_sessions
  add column task_id uuid references public.study_tasks (id) on delete set null,
  add column manual_override boolean not null default false,
  add column sync_updated_at timestamptz,
  add column change_seq bigint;

-- Backfill without letting set_updated_at() rewrite every existing row's server timestamp.
alter table public.study_sessions disable trigger set_study_sessions_updated_at;
update public.study_sessions as s
  set sync_updated_at = s.updated_at,
      change_seq = ordered.position
  from (
    select id, row_number() over (partition by user_id order by updated_at, id) as position
    from public.study_sessions
  ) as ordered
  where ordered.id = s.id;
alter table public.study_sessions enable trigger set_study_sessions_updated_at;

insert into public.study_session_sync_clocks (user_id, last_change_seq)
  select user_id, max(change_seq) from public.study_sessions group by user_id;

alter table public.study_sessions
  alter column sync_updated_at set not null,
  alter column sync_updated_at set default now(),
  alter column change_seq set not null;

comment on column public.study_sessions.sync_updated_at is
  'The client''s updatedAt: the last-writer-wins term of ADR 0012, compared exactly as '
  'SessionSyncMerge.wins does. Distinct from updated_at, which is the server clock.';
comment on column public.study_sessions.change_seq is
  'Per-user commit-ordered change position; the delta cursor of GET /v1/sync/sessions.';

create unique index study_sessions_user_id_change_seq_idx
  on public.study_sessions (user_id, change_seq);

-- Every write path, including a direct service-role update, advances the owner's clock, so no
-- change can land without becoming visible to the next delta. The upsert takes a row lock on the
-- owner's clock that is held until commit, which serialises one user's writers and makes the
-- issued order the commit order. Security definer because the clock is service-only while an
-- owner's own RLS-permitted write must still advance it; it touches only new.user_id's clock.
create function public.stamp_study_session_change_seq()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.study_session_sync_clocks as clock (user_id, last_change_seq)
    values (new.user_id, 1)
    on conflict (user_id) do update set last_change_seq = clock.last_change_seq + 1
    returning clock.last_change_seq into new.change_seq;
  return new;
end;
$$;

create trigger stamp_study_sessions_change_seq
  before insert or update on public.study_sessions
  for each row execute function public.stamp_study_session_change_seq();

comment on table public.study_sessions is
  'Completed study sessions only (ADR 0012); a running or paused timer never leaves the device. '
  'Their event logs replicate through study_session_events (ADR 0017), superseding the original '
  'migration''s claim that the log never leaves the device.';

-- ---------------------------------------------------------------------------------------------
-- study_session_events: the replicated log.
-- ---------------------------------------------------------------------------------------------
create table public.study_session_events (
  id text not null check (btrim(id) <> ''),
  session_id uuid not null references public.study_sessions (id) on delete cascade,
  type text not null check (type in (
    'STARTED', 'PAUSED', 'RESUMED', 'BREAK_STARTED', 'FOCUS_RESUMED', 'ACTIVITY_CONFIRMED', 'STOPPED'
  )),
  sequence bigint not null check (sequence >= 0),
  wall_clock timestamptz not null,
  primary key (session_id, id)
);

comment on table public.study_session_events is
  'Append-only event logs of stopped sessions (ADR 0017), merged by union on id and never '
  'overwritten (ADR 0012); listed in (sequence, id) order. Deliberately has no uptime_millis or '
  'boot_id: those are device-local monotonic-clock anchors, and another device''s boot id is an '
  'uninterpretable fact. The API drops them on the way in and never returns them. This table '
  'supersedes the 20260916090300 migration comment saying the log never leaves the device.';

alter table public.study_session_events enable row level security;

create policy "study_session_events_select_own" on public.study_session_events
  for select using (exists (
    select 1 from public.study_sessions s where s.id = session_id and s.user_id = auth.uid()
  ));

create policy "study_session_events_insert_own" on public.study_session_events
  for insert with check (exists (
    select 1 from public.study_sessions s where s.id = session_id and s.user_id = auth.uid()
  ));

-- No update or delete policy: events are never rewritten, and they go only when their session
-- (or its owner) is deleted, by cascade.

-- ---------------------------------------------------------------------------------------------
-- Wire projection shared by the pull.
-- ---------------------------------------------------------------------------------------------
create function public.sync_timestamp(p_value timestamptz)
returns text
language sql
immutable
as $$
  select to_char(p_value at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
$$;

-- ---------------------------------------------------------------------------------------------
-- GET /v1/sync/sessions. The Edge Function uses its service role, so the owner predicate is part
-- of the function rather than relying on RLS; the caller's id is verified before it is passed in.
-- ---------------------------------------------------------------------------------------------
create function public.sync_pull_study_sessions(p_user_id uuid, p_after bigint, p_limit integer)
returns table (change_seq bigint, session jsonb)
language sql
stable
set search_path = public
as $$
  select
    s.change_seq,
    jsonb_strip_nulls(jsonb_build_object(
      'id', s.id,
      'deviceId', s.device_id,
      'updatedAt', public.sync_timestamp(s.sync_updated_at),
      'startedAt', public.sync_timestamp(s.started_at),
      'endedAt', public.sync_timestamp(s.ended_at),
      'status', 'STOPPED',
      'subjectId', s.subject_id,
      'taskId', s.task_id,
      'note', s.note
    )) || jsonb_build_object(
      'deleted', s.deleted_at is not null,
      'manualOverride', s.manual_override,
      'countedMillis', round(s.counted_seconds * 1000)::bigint,
      'unverifiedMillis', round(s.unverified_seconds * 1000)::bigint,
      'events', coalesce((
        select jsonb_agg(jsonb_build_object(
          'id', e.id,
          'sessionId', e.session_id,
          'type', e.type,
          'sequence', e.sequence,
          'wallClock', public.sync_timestamp(e.wall_clock)
        ) order by e.sequence, e.id collate "C")
        from public.study_session_events e
        where e.session_id = s.id
      ), '[]'::jsonb)
    )
  from public.study_sessions s
  where s.user_id = p_user_id
    and s.change_seq > p_after
  order by s.change_seq
  limit p_limit
$$;

-- ---------------------------------------------------------------------------------------------
-- POST /v1/sync/sessions. Each change is resolved on its own, exactly as SessionSyncMerge.wins
-- resolves it on the device:
--
--   1. a different sync_updated_at: the later one wins;
--   2. an exact tie: the lexicographically greater device_id wins, compared under the "C"
--      collation (code-point order, as Kotlin's String.compareTo is for the BMP) and never under
--      the database's locale collation, which can order two ids differently from the device;
--   3. same instant, same device: a tombstone beats a live row.
--
-- The event log is unioned whatever the metadata outcome, as SessionSyncMerge.mergeEvents does;
-- a re-delivered event id is ignored, never an error and never an overwrite.
--
-- A change is accepted when it won, or when it is an exact replay of what the server already
-- holds (openapi: replaying an applied batch is a no-op). It is rejected when the server holds
-- something that beats it, which the next delta delivers — the ADR 0012 three-way case.
-- ---------------------------------------------------------------------------------------------
create function public.sync_push_study_sessions(p_user_id uuid, p_changes jsonb)
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
      -- Another account's id: never overwritten and never unioned into. Reported as rejected so
      -- nothing about the other row is revealed beyond "not stored".
      rejected := rejected || incoming_id::text;
      continue;
    end if;

    -- References are kept only when they resolve to the caller's own rows. Subjects and tasks do
    -- not sync yet, so this mirrors SyncDao.withResolvableSubject and never lets a session point
    -- at another user's subject or task.
    incoming_subject := null;
    if coalesce(change->>'subjectId', '') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
      select id into incoming_subject from public.subjects
        where id = (change->>'subjectId')::uuid and user_id = p_user_id;
    end if;
    incoming_task := null;
    if coalesce(change->>'taskId', '') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
      select id into incoming_task from public.study_tasks
        where id = (change->>'taskId')::uuid and user_id = p_user_id;
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

    -- Only the replicated fields are read; any device-local anchor in the payload is ignored.
    insert into public.study_session_events (id, session_id, type, sequence, wall_clock)
      select e->>'id', incoming_id, e->>'type', (e->>'sequence')::bigint, (e->>'wallClock')::timestamptz
      from jsonb_array_elements(coalesce(change->'events', '[]'::jsonb)) as e
      on conflict (session_id, id) do nothing;
    get diagnostics inserted = row_count;

    -- New events on a row whose metadata did not change still have to reach other devices, so
    -- the row is touched to move it past every existing cursor.
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

revoke all on function public.sync_timestamp(timestamptz) from public, anon, authenticated;
revoke all on function public.sync_pull_study_sessions(uuid, bigint, integer) from public, anon, authenticated;
revoke all on function public.sync_push_study_sessions(uuid, jsonb) from public, anon, authenticated;
revoke all on function public.stamp_study_session_change_seq() from public, anon, authenticated;
grant execute on function public.sync_timestamp(timestamptz) to service_role;
grant execute on function public.sync_pull_study_sessions(uuid, bigint, integer) to service_role;
grant execute on function public.sync_push_study_sessions(uuid, jsonb) to service_role;

alter table public.backend_observability_events
  drop constraint backend_observability_events_operation_check;
alter table public.backend_observability_events
  add constraint backend_observability_events_operation_check
  check (operation in (
    'initUpload', 'completeUpload', 'getDownloadUrl', 'delete', 'reapOrphans',
    'beginSignIn', 'signIn', 'refreshTokens', 'listSubjects', 'listTasks',
    'deleteAccount', 'pullSessionChanges', 'pushSessionChanges', 'unknown'
  ));
