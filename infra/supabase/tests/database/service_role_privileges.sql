-- The privileges the service role needs to reach a row, exercised as production has them.
--
-- Why session_sync.sql and record_sync.sql passed while every push failed in production: they
-- `set local role service_role` against a database created by the local Supabase stack, whose
-- bootstrap runs `alter default privileges in schema public grant all on tables to ...
-- service_role`. Every table a migration creates there is therefore already readable and writable
-- by service_role before any `grant` in the migration, so those suites exercise the *logic* of the
-- sync functions with privileges the deployed database never handed out. A missing grant is
-- invisible to them by construction, not by oversight.
--
-- This file removes that safety net: it revokes the service role's privileges on the tables the
-- invoker-rights functions touch, re-applies only what the migrations declare in
-- service_role_table_grants, and then runs both pushes and both pulls with a non-empty payload. A
-- verb missing from the manifest fails here with the same `permission denied for table ...` the
-- outage produced.
--
-- Run with `supabase test db --local`, alongside session_sync.sql and record_sync.sql.
begin;

create extension if not exists pgtap with schema extensions;

select plan(10);

insert into auth.users (id, email) values
  ('11111111-1111-1111-1111-111111111111', 'alice@example.com');

insert into public.subjects (id, user_id, name, color_argb, device_id) values
  ('a1111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 'Chemistry', 16711680, 'device-a');

-- The declared set is the only privilege the rest of this file runs on. Revoked as the owner,
-- before any `set role`, and rolled back with the transaction like every other test here.
revoke all on
  public.study_sessions,
  public.study_session_events,
  public.sync_records,
  public.subjects,
  public.study_tasks,
  public.auth_signin_challenges,
  public.storage_uploads,
  public.storage_url_audit,
  public.backend_observability_events,
  public.account_deletion_receipts,
  public.passkey_credentials
  from service_role;
select public.apply_service_role_table_grants();

set local role service_role;

-- A non-empty batch: an empty one never reaches a table and so never needed a privilege, which is
-- how this class of bug stays hidden until a device actually has something to send.
create function pg_temp.session_change(p_id text, p_updated text, p_device text) returns jsonb language sql as $$
  select jsonb_build_object(
    'id', p_id, 'deviceId', p_device, 'updatedAt', p_updated,
    'startedAt', '2026-03-01T09:00:00Z', 'endedAt', '2026-03-01T10:00:00Z', 'status', 'STOPPED',
    'subjectId', 'a1111111-1111-1111-1111-111111111111',
    'deleted', false, 'manualOverride', false, 'countedMillis', 3600000, 'unverifiedMillis', 0,
    'events', jsonb_build_array(jsonb_build_object(
      'id', 'e1', 'sessionId', p_id, 'type', 'STARTED', 'sequence', 0,
      'wallClock', '2026-03-01T09:00:00Z')))
$$;

select is(
  public.sync_push_study_sessions(
    '11111111-1111-1111-1111-111111111111',
    jsonb_build_array(pg_temp.session_change('c0000000-0000-0000-0000-00000000000a', '2026-03-01T10:00:10Z', 'device-a'))
  ) -> 'acceptedIds',
  '["c0000000-0000-0000-0000-00000000000a"]'::jsonb,
  'the service role can insert a pushed session, its event log and resolve its subject reference'
);

-- The second push takes the update path — a separate privilege from the insert above, and the one
-- a device exercises on every edit after the first.
select is(
  public.sync_push_study_sessions(
    '11111111-1111-1111-1111-111111111111',
    jsonb_build_array(pg_temp.session_change('c0000000-0000-0000-0000-00000000000a', '2026-03-01T10:00:20Z', 'device-b'))
  ) -> 'acceptedIds',
  '["c0000000-0000-0000-0000-00000000000a"]'::jsonb,
  'the service role can update a session a later write won'
);

select is(
  (select count(*) from public.sync_pull_study_sessions('11111111-1111-1111-1111-111111111111', 0, 50)),
  1::bigint,
  'the service role can read the session delta, event log included'
);

select is(
  public.sync_push_records(
    '11111111-1111-1111-1111-111111111111',
    jsonb_build_array(jsonb_build_object(
      'entityType', 'task', 'id', 't-a', 'deviceId', 'device-a', 'updatedAt', '2026-03-01T10:00:10Z',
      'deleted', false, 'schemaVersion', 1, 'payload', jsonb_build_object('id', 't-a', 'title', 'A')))
  ) -> 'acceptedIds',
  '["t-a"]'::jsonb,
  'the service role can insert a pushed record'
);

select is(
  public.sync_push_records(
    '11111111-1111-1111-1111-111111111111',
    jsonb_build_array(jsonb_build_object(
      'entityType', 'task', 'id', 't-a', 'deviceId', 'device-a', 'updatedAt', '2026-03-01T10:00:20Z',
      'deleted', false, 'schemaVersion', 1, 'payload', jsonb_build_object('id', 't-a', 'title', 'B')))
  ) -> 'acceptedIds',
  '["t-a"]'::jsonb,
  'the service role can update a record a later write won'
);

select is(
  (select count(*) from public.sync_pull_records('11111111-1111-1111-1111-111111111111', 0, 50)),
  1::bigint,
  'the service role can read the record delta'
);

reset role;

-- Independently enumerated from every direct database/databaseJson call in storage and api.
-- Check after stripping defaults and replaying the manifest, not against local bootstrap grants.
select is_empty(
  $$
    select required.table_name || ' needs ' || required.privilege
    from (values
      ('storage_uploads', 'select'), ('storage_uploads', 'update'),
      ('storage_url_audit', 'insert'), ('backend_observability_events', 'insert'),
      ('subjects', 'select'),
      ('account_deletion_receipts', 'select'), ('account_deletion_receipts', 'insert'),
      ('auth_signin_challenges', 'select'), ('auth_signin_challenges', 'insert'),
      ('auth_signin_challenges', 'update'),
      ('passkey_credentials', 'select'), ('passkey_credentials', 'insert'),
      ('passkey_credentials', 'update')
    ) as required(table_name, privilege)
    where not has_table_privilege('service_role', 'public.' || required.table_name, required.privilege)
  $$,
  'service_role has every privilege required by direct storage and api queries'
);

set local role service_role;

-- Exercise identity defaults without granting access to their sequences.
select lives_ok(
  $$insert into public.storage_url_audit (user_id, operation, object_key, expires_at)
    values ('11111111-1111-1111-1111-111111111111', 'download', 'test-object', now() + interval '10 minutes')$$,
  'storage can write its URL audit'
);
select lives_ok(
  $$insert into public.backend_observability_events (request_id, operation, status, duration_ms)
    values ('privilege-test', 'unknown', 503, 0)$$,
  'both Edge Functions can write observability events'
);

reset role;

-- The tripwire for the next RPC rather than for this one: a function that runs with invoker rights
-- and can be executed by the service role must have every public table it names declared, or it
-- reproduces this outage the first time its path is taken.
select is_empty(
  $$
    select p.proname || ' reads ' || referenced.relname
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace and n.nspname = 'public'
    cross join lateral (
      select c.relname
      from pg_class c
      join pg_namespace cn on cn.oid = c.relnamespace and cn.nspname = 'public'
      where c.relkind = 'r'
        and p.prosrc ~ ('public\.' || c.relname || '\M')
    ) as referenced
    where not p.prosecdef
      and has_function_privilege('service_role', p.oid, 'execute')
      and not exists (
        select 1 from public.service_role_table_grants g where g.table_name = referenced.relname
      )
  $$,
  'every table an invoker-rights service-role function names is declared in the grant manifest'
);

select * from finish();
rollback;
