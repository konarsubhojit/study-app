-- Authorisation tests: prove that row-level security makes cross-user access impossible at the
-- data layer, for every table introduced by the migrations in ../../migrations.
--
-- Run with `supabase test db --local` (see infra/README.md). Each table gets the same four
-- checks: user A can read/write their own row, and every attempt by user B to read, update or
-- delete user A's row returns zero affected rows rather than an error — RLS filters rows out
-- silently, which is exactly the behaviour we want to pin down here.
begin;

create extension if not exists pgtap with schema extensions;

-- NOTE: keep this in sync with the number of ok/is/lives_ok/is_empty/throws_ok assertions below —
-- pgTAP's plan() count is a manual tripwire: too few and the suite silently under-reports, too
-- many and it fails loudly, which is why any assertion added or removed must update this number.
select plan(56);

-- Two distinct users, never created via auth.users directly in tests: we insert straight into
-- auth.users because there is no GoTrue running inside `supabase test db`, only Postgres.
insert into auth.users (id, email) values
  ('11111111-1111-1111-1111-111111111111', 'alice@example.com'),
  ('22222222-2222-2222-2222-222222222222', 'bob@example.com');

-- profiles rows are created by the on_auth_user_created trigger; confirm that happened.
select ok(
  exists(select 1 from public.profiles where id = '11111111-1111-1111-1111-111111111111'),
  'profile auto-created for alice'
);
select ok(
  exists(select 1 from public.profiles where id = '22222222-2222-2222-2222-222222222222'),
  'profile auto-created for bob'
);

-- Seed one row per table as alice, via service_role so RLS does not get in the way of setup.
set local role service_role;

insert into public.subjects (id, user_id, name, color_argb, device_id) values
  ('a1111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 'Chemistry', 16711680, 'device-a');

insert into public.study_sessions
    (id, user_id, subject_id, started_at, ended_at, counted_seconds, device_id)
  values
    ('a2222222-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111',
     'a1111111-1111-1111-1111-111111111111', now() - interval '1 hour', now(), 3600, 'device-a');

insert into public.study_tasks (id, user_id, subject_id, title, device_id) values
  ('a3333333-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111',
   'a1111111-1111-1111-1111-111111111111', 'Revise chapter 4', 'device-a');

insert into public.reminders (id, user_id, task_id, device_id) values
  ('a4444444-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111',
   'a3333333-1111-1111-1111-111111111111', 'device-a');

insert into public.material_folders (id, user_id, name, device_id) values
  ('a5555555-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 'Past papers', 'device-a');

insert into public.materials
    (id, user_id, folder_id, display_name, mime_type, size_bytes, content_hash, storage_key, device_id)
  values
    ('a6666666-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111',
     'a5555555-1111-1111-1111-111111111111', 'notes.pdf', 'application/pdf', 1024,
     repeat('a', 64),
     '11111111-1111-1111-1111-111111111111/' || repeat('a', 64), 'device-a');

insert into public.sync_cursors (user_id, device_id, entity_type, cursor) values
  ('11111111-1111-1111-1111-111111111111', 'device-a', 'subject', now());

reset role;

-- ---------------------------------------------------------------------------------------------
-- alice, acting as herself, can see and change every row she owns.
-- ---------------------------------------------------------------------------------------------
set local role authenticated;
set local request.jwt.claim.sub = '11111111-1111-1111-1111-111111111111';
set local request.jwt.claims = '{"sub": "11111111-1111-1111-1111-111111111111", "role": "authenticated"}';

select is((select count(*) from public.subjects)::int, 1, 'alice sees her own subject');
select is((select count(*) from public.study_sessions)::int, 1, 'alice sees her own session');
select is((select count(*) from public.study_tasks)::int, 1, 'alice sees her own task');
select is((select count(*) from public.reminders)::int, 1, 'alice sees her own reminder');
select is((select count(*) from public.material_folders)::int, 1, 'alice sees her own folder');
select is((select count(*) from public.materials)::int, 1, 'alice sees her own material');
select is((select count(*) from public.sync_cursors)::int, 1, 'alice sees her own sync cursor');

select lives_ok(
  $$ update public.subjects set archived = true where id = 'a1111111-1111-1111-1111-111111111111' $$,
  'alice can update her own subject'
);

-- ---------------------------------------------------------------------------------------------
-- bob, acting as himself, sees none of alice's rows: RLS filters them out rather than erroring.
-- ---------------------------------------------------------------------------------------------
reset role;
set local role authenticated;
set local request.jwt.claim.sub = '22222222-2222-2222-2222-222222222222';
set local request.jwt.claims = '{"sub": "22222222-2222-2222-2222-222222222222", "role": "authenticated"}';

select is((select count(*) from public.subjects)::int, 0, 'bob cannot see alice''s subject');
select is((select count(*) from public.study_sessions)::int, 0, 'bob cannot see alice''s session');
select is((select count(*) from public.study_tasks)::int, 0, 'bob cannot see alice''s task');
select is((select count(*) from public.reminders)::int, 0, 'bob cannot see alice''s reminder');
select is((select count(*) from public.material_folders)::int, 0, 'bob cannot see alice''s folder');
select is((select count(*) from public.materials)::int, 0, 'bob cannot see alice''s material');
select is((select count(*) from public.sync_cursors)::int, 0, 'bob cannot see alice''s sync cursor');

-- Writes: bob's update/delete statements succeed as no-ops (zero rows matched), never touching
-- alice's data and never raising a permission error that would leak the row's existence.
select is_empty(
  $$ update public.subjects set archived = true
       where id = 'a1111111-1111-1111-1111-111111111111' returning 1 $$,
  'bob''s update of alice''s subject affects no rows'
);
select is_empty(
  $$ delete from public.subjects
       where id = 'a1111111-1111-1111-1111-111111111111' returning 1 $$,
  'bob''s delete of alice''s subject affects no rows'
);
select is_empty(
  $$ update public.study_sessions set note = 'hacked'
       where id = 'a2222222-1111-1111-1111-111111111111' returning 1 $$,
  'bob''s update of alice''s session affects no rows'
);
select is_empty(
  $$ delete from public.study_sessions
       where id = 'a2222222-1111-1111-1111-111111111111' returning 1 $$,
  'bob''s delete of alice''s session affects no rows'
);
select is_empty(
  $$ update public.study_tasks set title = 'hacked'
       where id = 'a3333333-1111-1111-1111-111111111111' returning 1 $$,
  'bob''s update of alice''s task affects no rows'
);
select is_empty(
  $$ delete from public.study_tasks
       where id = 'a3333333-1111-1111-1111-111111111111' returning 1 $$,
  'bob''s delete of alice''s task affects no rows'
);
select is_empty(
  $$ update public.materials set display_name = 'hacked'
       where id = 'a6666666-1111-1111-1111-111111111111' returning 1 $$,
  'bob''s update of alice''s material affects no rows'
);
select is_empty(
  $$ delete from public.materials
       where id = 'a6666666-1111-1111-1111-111111111111' returning 1 $$,
  'bob''s delete of alice''s material affects no rows'
);
select is_empty(
  $$ update public.reminders set lead_time_seconds = 999
       where id = 'a4444444-1111-1111-1111-111111111111' returning 1 $$,
  'bob''s update of alice''s reminder affects no rows'
);
select is_empty(
  $$ delete from public.reminders
       where id = 'a4444444-1111-1111-1111-111111111111' returning 1 $$,
  'bob''s delete of alice''s reminder affects no rows'
);
select is_empty(
  $$ update public.material_folders set name = 'hacked'
       where id = 'a5555555-1111-1111-1111-111111111111' returning 1 $$,
  'bob''s update of alice''s folder affects no rows'
);
select is_empty(
  $$ delete from public.material_folders
       where id = 'a5555555-1111-1111-1111-111111111111' returning 1 $$,
  'bob''s delete of alice''s folder affects no rows'
);
select is_empty(
  $$ update public.sync_cursors set cursor = now()
       where user_id = '11111111-1111-1111-1111-111111111111' and device_id = 'device-a'
       returning 1 $$,
  'bob''s update of alice''s sync cursor affects no rows'
);
select is_empty(
  $$ delete from public.sync_cursors
       where user_id = '11111111-1111-1111-1111-111111111111' and device_id = 'device-a'
       returning 1 $$,
  'bob''s delete of alice''s sync cursor affects no rows'
);

-- bob cannot insert a row claiming to be alice's, even though he supplies alice's user_id.
select throws_ok(
  $$ insert into public.subjects (id, user_id, name, color_argb, device_id)
       values ('b0000000-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111',
               'Impersonated', 0, 'device-b') $$,
  '42501',
  null,
  'bob cannot insert a subject owned by alice'
);

-- bob's own row is still fully usable: RLS is per-row, not a blanket lockout.
select lives_ok(
  $$ insert into public.subjects (id, user_id, name, color_argb, device_id)
       values ('b1111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222',
               'Physics', 255, 'device-b') $$,
  'bob can insert his own subject'
);
select is((select count(*) from public.subjects)::int, 1, 'bob now sees exactly his own subject');

-- Storage service internals are never exposed through PostgREST. All access goes through the Edge
-- Function, which derives object keys from its verified JWT rather than trusting a client prefix.
select ok(
  not has_table_privilege('authenticated', 'public.storage_accounts', 'select'),
  'clients cannot inspect account quota records'
);
select ok(
  not has_table_privilege('authenticated', 'public.storage_uploads', 'select'),
  'clients cannot inspect provider upload handles'
);
select ok(
  not has_table_privilege('authenticated', 'public.storage_url_audit', 'select'),
  'clients cannot inspect URL issuance audit records'
);
select ok(
  not has_table_privilege('authenticated', 'public.storage_rate_events', 'insert'),
  'clients cannot bypass rate limiting by changing counters'
);
select ok(
  not has_function_privilege(
    'authenticated',
    'public.storage_reserve_upload(uuid,text,text,text,bigint,jsonb,timestamptz)',
    'execute'
  ),
  'clients cannot reserve arbitrary object keys through the quota function'
);
select ok(
  not has_table_privilege('authenticated', 'public.backend_observability_events', 'insert'),
  'clients cannot forge backend observability events'
);
select ok(
  not has_table_privilege('authenticated', 'public.backend_alert_policies', 'select'),
  'clients cannot inspect alert routing policy'
);
select ok(
  not has_table_privilege('authenticated', 'public.backend_cost_assumptions', 'select'),
  'clients cannot inspect cost model assumptions'
);
select ok(
  not has_table_privilege('authenticated', 'public.storage_lifecycle_rules', 'select'),
  'clients cannot inspect storage lifecycle policy'
);

-- The storage-key/owner check constraint independently blocks a materials row from pointing at
-- someone else's object prefix, even for a service-role write.
reset role;
set local role service_role;
select throws_ok(
  $$ insert into public.materials
       (id, user_id, folder_id, display_name, mime_type, size_bytes, content_hash, storage_key, device_id)
       values ('a7777777-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222',
               null, 'stolen.pdf', 'application/pdf', 1,
               repeat('b', 64),
               '11111111-1111-1111-1111-111111111111/' || repeat('b', 64), 'device-b') $$,
  '23514',
  null,
  'a material row cannot claim another user''s storage-key prefix'
);

insert into public.storage_accounts (user_id, quota_bytes)
  values ('11111111-1111-1111-1111-111111111111', 100);
select lives_ok(
  $$ select * from public.storage_reserve_upload(
       '11111111-1111-1111-1111-111111111111',
       '11111111-1111-1111-1111-111111111111/' || repeat('c', 64),
       repeat('c', 64), 'application/pdf', 60, '["checksum"]'::jsonb, now() + interval '1 hour'
     ) $$,
  'an upload inside the remaining quota is reserved'
);
select lives_ok(
  $$ select * from public.storage_reserve_upload(
       '11111111-1111-1111-1111-111111111111',
       '11111111-1111-1111-1111-111111111111/' || repeat('c', 64),
       repeat('c', 64), 'application/pdf', 60, '["checksum"]'::jsonb, now() + interval '1 hour'
     ) $$,
  'retrying the same upload reuses its reservation'
);
select is(
  (select count(*) from public.storage_uploads where content_hash = repeat('c', 64))::int,
  1,
  'an upload retry does not double-count quota'
);
select throws_ok(
  $$ select * from public.storage_reserve_upload(
       '11111111-1111-1111-1111-111111111111',
       '11111111-1111-1111-1111-111111111111/' || repeat('d', 64),
       repeat('d', 64), 'application/pdf', 50, '["checksum"]'::jsonb, now() + interval '1 hour'
     ) $$,
  'P0001',
  'quota_exceeded',
  'a reservation over the remaining quota is rejected'
);
update public.storage_uploads
  set expires_at = now() - interval '1 minute', provider_upload_id = 'provider-upload'
  where content_hash = repeat('c', 64);
select is(
  (select count(*) from public.storage_claim_expired_uploads(100))::int,
  1,
  'the reaper claims an expired upload'
);
update public.storage_uploads set reaping_at = now() - interval '20 minutes'
  where content_hash = repeat('c', 64);
select is(
  (select count(*) from public.storage_claim_expired_uploads(100))::int,
  1,
  'a stale reaping claim is recovered after a worker crash'
);
select lives_ok(
  $$ insert into public.backend_observability_events
       (request_id, operation, status, duration_ms, error_code, egress_bytes)
     values ('trace-1', 'completeUpload', 503, 1250, 'storage_unavailable', 0) $$,
  'the service role can record sanitized backend telemetry'
);
select is(
  (select request_count from public.backend_health_daily where day = current_date),
  1::bigint,
  'the health dashboard view counts backend requests'
);
select is(
  (select auth_failures from public.backend_health_daily where day = current_date),
  0::bigint,
  'the health dashboard view exposes auth failure counts'
);
insert into public.storage_uploads
    (user_id, object_key, content_hash, content_type, size_bytes, part_checksums, state, expires_at, completed_at)
  values (
    '11111111-1111-1111-1111-111111111111',
    '11111111-1111-1111-1111-111111111111/' || repeat('e', 64),
    repeat('e', 64),
    'application/pdf',
    40,
    '[]'::jsonb,
    'ready',
    now() + interval '1 hour',
    now()
  );
select is(
  (select ready_bytes_added from public.backend_storage_growth_daily where day = current_date),
  40::bigint,
  'the storage growth dashboard view totals ready bytes'
);
select ok(
  (select projected_monthly_usd_per_1000_active_users from public.backend_cost_projection) >= 0,
  'the cost dashboard view projects monthly spend per 1000 active users'
);
select is(
  (select count(*) from public.storage_lifecycle_rules where enabled)::int,
  3,
  'storage lifecycle guardrails are registered'
);
reset role;

select * from finish();
rollback;
