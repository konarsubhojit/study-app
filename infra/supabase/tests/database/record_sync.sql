-- Record sync behaviour (ADR 0018): tasks and materials resolve by the same rule as sessions, on
-- the envelope alone, and the server must agree with the client's LastWriteWins rule for rule.
-- Each block mirrors a case in DocumentSyncMergeTest / TwoDeviceConvergenceTest.
--
-- Run with `supabase test db --local`, alongside session_sync.sql.
begin;

create extension if not exists pgtap with schema extensions;

select plan(18);

insert into auth.users (id, email) values
  ('11111111-1111-1111-1111-111111111111', 'alice@example.com'),
  ('22222222-2222-2222-2222-222222222222', 'bob@example.com');

set local role service_role;

-- A SyncRecord as the Edge Function forwards it.
create function pg_temp.record(
  p_id text, p_updated text, p_device text, p_deleted boolean default false,
  p_title text default 'title', p_type text default 'task'
) returns jsonb language sql as $$
  select jsonb_build_object(
    'entityType', p_type, 'id', p_id, 'deviceId', p_device, 'updatedAt', p_updated,
    'deleted', p_deleted, 'schemaVersion', 1, 'payload', jsonb_build_object('id', p_id, 'title', p_title))
$$;

create function pg_temp.push(p_user text, variadic p_changes jsonb[]) returns jsonb language sql as $$
  select public.sync_push_records(p_user::uuid, to_jsonb(p_changes))
$$;

create function pg_temp.state(p_user text, p_id text, p_type text default 'task') returns text language sql as $$
  select concat_ws('|', device_id, payload->>'title', public.sync_timestamp(sync_updated_at), deleted::text)
  from public.sync_records where user_id = p_user::uuid and entity_type = p_type and entity_id = p_id
$$;

-- ---------------------------------------------------------------------------------------------
-- 1. Two devices' edits applied in either order converge on the later write.
-- ---------------------------------------------------------------------------------------------
select pg_temp.push('11111111-1111-1111-1111-111111111111', pg_temp.record('t-a', '2026-03-01T10:00:10Z', 'device-a', false, 'A'));
select pg_temp.push('11111111-1111-1111-1111-111111111111', pg_temp.record('t-a', '2026-03-01T10:00:20Z', 'device-b', false, 'B'));
select pg_temp.push('11111111-1111-1111-1111-111111111111', pg_temp.record('t-b', '2026-03-01T10:00:20Z', 'device-b', false, 'B'));
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111', pg_temp.record('t-b', '2026-03-01T10:00:10Z', 'device-a', false, 'A')),
  '{"acceptedIds": [], "rejectedIds": ["t-b"]}'::jsonb,
  'an older write is rejected'
);
select is(pg_temp.state('11111111-1111-1111-1111-111111111111', 't-a'),
  pg_temp.state('11111111-1111-1111-1111-111111111111', 't-b'),
  'the same two edits applied in either order converge');
select is(pg_temp.state('11111111-1111-1111-1111-111111111111', 't-a'),
  'device-b|B|2026-03-01T10:00:20.000000Z|false', 'the later updatedAt wins, payload and all');

-- ---------------------------------------------------------------------------------------------
-- 2. An exact tie goes to the greater deviceId in code-point order ('a' > 'B'), then to the
--    tombstone; an exact replay is accepted without a change.
-- ---------------------------------------------------------------------------------------------
select pg_temp.push('11111111-1111-1111-1111-111111111111', pg_temp.record('t-tie', '2026-03-01T10:00:00Z', 'B', false, 'B'));
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111', pg_temp.record('t-tie', '2026-03-01T10:00:00Z', 'a', false, 'a')),
  '{"acceptedIds": ["t-tie"], "rejectedIds": []}'::jsonb,
  'tie: the greater deviceId in code-point order wins'
);
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111', pg_temp.record('t-tie', '2026-03-01T10:00:00Z', 'B', false, 'B')),
  '{"acceptedIds": [], "rejectedIds": ["t-tie"]}'::jsonb,
  'tie: the lesser deviceId loses whichever order it arrives in'
);
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111', pg_temp.record('t-tie', '2026-03-01T10:00:00Z', 'a', true, 'a')),
  '{"acceptedIds": ["t-tie"], "rejectedIds": []}'::jsonb,
  'same instant, same device: the tombstone wins'
);
select is(pg_temp.state('11111111-1111-1111-1111-111111111111', 't-tie'),
  'a|a|2026-03-01T10:00:00.000000Z|true', 'the tombstone is stored rather than the row removed');
create temp table before_replay as select change_seq from public.sync_records where entity_id = 't-tie';
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111', pg_temp.record('t-tie', '2026-03-01T10:00:00Z', 'a', true, 'a')),
  '{"acceptedIds": ["t-tie"], "rejectedIds": []}'::jsonb,
  'an exact replay is accepted'
);
select is(
  (select change_seq from public.sync_records where entity_id = 't-tie'),
  (select change_seq from before_replay),
  'an exact replay writes nothing, so it does not reappear in anyone''s delta'
);

-- ---------------------------------------------------------------------------------------------
-- 3. Tasks and materials are separate namespaces, and the delta is commit-ordered.
-- ---------------------------------------------------------------------------------------------
select pg_temp.push('11111111-1111-1111-1111-111111111111',
  pg_temp.record('t-a', '2026-01-01T00:00:00Z', 'device-a', false, 'material', 'material'));
select is(pg_temp.state('11111111-1111-1111-1111-111111111111', 't-a', 'material'),
  'device-a|material|2026-01-01T00:00:00.000000Z|false', 'a material may share a task''s id');
select is(
  (select record->>'entityType' from public.sync_pull_records('11111111-1111-1111-1111-111111111111', 0, 100)
    order by change_seq desc limit 1),
  'material',
  'a write with an older updatedAt still lands at the head of the delta'
);
select is(
  (select record from public.sync_pull_records('11111111-1111-1111-1111-111111111111', 0, 100)
    order by change_seq desc limit 1),
  jsonb_build_object(
    'entityType', 'material', 'id', 't-a', 'deviceId', 'device-a',
    'updatedAt', '2026-01-01T00:00:00.000000Z', 'deleted', false, 'schemaVersion', 1,
    'payload', jsonb_build_object('id', 't-a', 'title', 'material')),
  'the projection matches SyncRecordDto field for field'
);

-- ---------------------------------------------------------------------------------------------
-- 4. Ownership: ids are per owner, so another account's push creates its own row and never
--    touches or reveals alice's.
-- ---------------------------------------------------------------------------------------------
select is(
  pg_temp.push('22222222-2222-2222-2222-222222222222', pg_temp.record('t-a', '2030-01-01T00:00:00Z', 'zzz', true, 'hijack')),
  '{"acceptedIds": ["t-a"], "rejectedIds": []}'::jsonb,
  'bob''s push of the same id is stored as his own record'
);
select is(pg_temp.state('11111111-1111-1111-1111-111111111111', 't-a'),
  'device-b|B|2026-03-01T10:00:20.000000Z|false', 'alice''s record is unchanged');
select is(
  (select count(*) from public.sync_pull_records('22222222-2222-2222-2222-222222222222', 0, 1000))::int, 1,
  'bob''s delta contains only his own record'
);

-- ---------------------------------------------------------------------------------------------
-- 5. A session keeps its task link now that tasks replicate as records.
-- ---------------------------------------------------------------------------------------------
select public.sync_push_study_sessions('11111111-1111-1111-1111-111111111111', jsonb_build_array(jsonb_build_object(
  'id', 'f0000000-0000-0000-0000-000000000001', 'deviceId', 'device-a', 'updatedAt', '2026-03-01T10:00:00Z',
  'startedAt', '2026-03-01T09:00:00Z', 'endedAt', '2026-03-01T10:00:00Z', 'status', 'STOPPED',
  'taskId', 'f1111111-1111-1111-1111-111111111111', 'countedMillis', 3600000, 'unverifiedMillis', 0)));
select is(
  (select task_id from public.study_sessions where id = 'f0000000-0000-0000-0000-000000000001'),
  'f1111111-1111-1111-1111-111111111111'::uuid,
  'a session keeps its task id'
);

reset role;
set local role authenticated;
select ok(
  not has_function_privilege('authenticated', 'public.sync_push_records(uuid,jsonb)', 'execute')
    and not has_function_privilege('authenticated', 'public.sync_pull_records(uuid,bigint,integer)', 'execute'),
  'clients cannot invoke the service-role record push or pull'
);
reset role;

-- Account deletion (DELETE /v1/account deletes the Auth user) removes every record and clock.
delete from auth.users where id = '22222222-2222-2222-2222-222222222222';
select is(
  (select count(*) from public.sync_records where user_id = '22222222-2222-2222-2222-222222222222')
    + (select count(*) from public.sync_record_clocks where user_id = '22222222-2222-2222-2222-222222222222'),
  0::bigint,
  'deleting the account deletes its records and change clock'
);

select * from finish();
rollback;
