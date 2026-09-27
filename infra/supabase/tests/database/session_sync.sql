-- Session sync behaviour: the server's conflict resolution must agree with the client's
-- SessionSyncMerge rule for rule (ADR 0012), or two devices diverge permanently. Each block
-- mirrors a case in SessionSyncMergeTest / TwoDeviceConvergenceTest.
--
-- Run with `supabase test db --local`, alongside authorization.sql.
begin;

create extension if not exists pgtap with schema extensions;

select plan(47);

insert into auth.users (id, email) values
  ('11111111-1111-1111-1111-111111111111', 'alice@example.com'),
  ('22222222-2222-2222-2222-222222222222', 'bob@example.com');

set local role service_role;

-- A SyncSession as the Edge Function forwards it, anchors already stripped.
create function pg_temp.change(
  p_id text, p_updated text, p_device text,
  p_deleted boolean default false, p_note text default null, p_events jsonb default '[]'
) returns jsonb language sql as $$
  select jsonb_build_object(
    'id', p_id, 'deviceId', p_device, 'updatedAt', p_updated,
    'startedAt', '2026-03-01T09:00:00Z', 'endedAt', '2026-03-01T10:00:00Z', 'status', 'STOPPED',
    'note', p_note, 'deleted', p_deleted, 'manualOverride', false,
    'countedMillis', 3600000, 'unverifiedMillis', 1500, 'events', p_events)
$$;

create function pg_temp.event(p_session text, p_id text, p_sequence int) returns jsonb language sql as $$
  select jsonb_build_object('id', p_id, 'sessionId', p_session, 'type', 'STARTED',
    'sequence', p_sequence, 'wallClock', '2026-03-01T09:00:00Z')
$$;

create function pg_temp.push(p_user text, variadic p_changes jsonb[]) returns jsonb language sql as $$
  select public.sync_push_study_sessions(p_user::uuid, to_jsonb(p_changes))
$$;

create function pg_temp.state(p_id text) returns text language sql as $$
  select concat_ws('|', device_id, coalesce(note, ''), public.sync_timestamp(sync_updated_at), (deleted_at is not null)::text)
  from public.study_sessions where id = p_id::uuid
$$;

-- ---------------------------------------------------------------------------------------------
-- 1. Two devices' changes applied in both orders converge to the same state.
-- ---------------------------------------------------------------------------------------------
select pg_temp.push('11111111-1111-1111-1111-111111111111',
  pg_temp.change('c0000000-0000-0000-0000-00000000000a', '2026-03-01T10:00:10Z', 'device-a', false, 'from A'));
select pg_temp.push('11111111-1111-1111-1111-111111111111',
  pg_temp.change('c0000000-0000-0000-0000-00000000000a', '2026-03-01T10:00:20Z', 'device-b', false, 'from B'));
select pg_temp.push('11111111-1111-1111-1111-111111111111',
  pg_temp.change('c0000000-0000-0000-0000-00000000000b', '2026-03-01T10:00:20Z', 'device-b', false, 'from B'));
select pg_temp.push('11111111-1111-1111-1111-111111111111',
  pg_temp.change('c0000000-0000-0000-0000-00000000000b', '2026-03-01T10:00:10Z', 'device-a', false, 'from A'));
select is(
  pg_temp.state('c0000000-0000-0000-0000-00000000000a'),
  pg_temp.state('c0000000-0000-0000-0000-00000000000b'),
  'the same two changes applied in either order converge'
);
select is(
  pg_temp.state('c0000000-0000-0000-0000-00000000000a'),
  'device-b|from B|2026-03-01T10:00:20.000000Z|false',
  'the later updatedAt wins'
);

-- ---------------------------------------------------------------------------------------------
-- 2. An exact updatedAt tie goes to the lexicographically greater deviceId, in code-point order.
--    'a' (U+0061) > 'B' (U+0042), as Kotlin's String.compareTo says; a locale collation would
--    say the opposite, which is exactly the silent disagreement this pins down.
-- ---------------------------------------------------------------------------------------------
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111',
    pg_temp.change('c0000000-0000-0000-0000-00000000001a', '2026-03-01T10:00:00Z', 'B', false, 'from B')),
  '{"acceptedIds": ["c0000000-0000-0000-0000-00000000001a"], "rejectedIds": []}'::jsonb,
  'tie case: first copy of a new session is accepted'
);
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111',
    pg_temp.change('c0000000-0000-0000-0000-00000000001a', '2026-03-01T10:00:00Z', 'a', false, 'from a')),
  '{"acceptedIds": ["c0000000-0000-0000-0000-00000000001a"], "rejectedIds": []}'::jsonb,
  'an exact tie from the greater deviceId replaces the lesser one'
);
select pg_temp.push('11111111-1111-1111-1111-111111111111',
  pg_temp.change('c0000000-0000-0000-0000-00000000001b', '2026-03-01T10:00:00Z', 'a', false, 'from a'));
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111',
    pg_temp.change('c0000000-0000-0000-0000-00000000001b', '2026-03-01T10:00:00Z', 'B', false, 'from B')),
  '{"acceptedIds": [], "rejectedIds": ["c0000000-0000-0000-0000-00000000001b"]}'::jsonb,
  'an exact tie from the lesser deviceId is rejected'
);
select is(pg_temp.state('c0000000-0000-0000-0000-00000000001a'), 'a|from a|2026-03-01T10:00:00.000000Z|false',
  'tie resolved to the greater deviceId (arrival order lesser, greater)');
select is(pg_temp.state('c0000000-0000-0000-0000-00000000001b'), 'a|from a|2026-03-01T10:00:00.000000Z|false',
  'tie resolved to the greater deviceId (arrival order greater, lesser)');

-- ---------------------------------------------------------------------------------------------
-- 3. On a full tie (same instant, same device) a tombstone beats a live row, in either order.
-- ---------------------------------------------------------------------------------------------
select pg_temp.push('11111111-1111-1111-1111-111111111111',
  pg_temp.change('c0000000-0000-0000-0000-00000000002a', '2026-03-01T10:00:00Z', 'device-a', false));
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111',
    pg_temp.change('c0000000-0000-0000-0000-00000000002a', '2026-03-01T10:00:00Z', 'device-a', true)),
  '{"acceptedIds": ["c0000000-0000-0000-0000-00000000002a"], "rejectedIds": []}'::jsonb,
  'a tombstone replaces a live row on a full tie'
);
select pg_temp.push('11111111-1111-1111-1111-111111111111',
  pg_temp.change('c0000000-0000-0000-0000-00000000002b', '2026-03-01T10:00:00Z', 'device-a', true));
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111',
    pg_temp.change('c0000000-0000-0000-0000-00000000002b', '2026-03-01T10:00:00Z', 'device-a', false)),
  '{"acceptedIds": [], "rejectedIds": ["c0000000-0000-0000-0000-00000000002b"]}'::jsonb,
  'a live row does not replace a tombstone on a full tie'
);
select ok(
  (select bool_and(deleted_at is not null) from public.study_sessions
    where id in ('c0000000-0000-0000-0000-00000000002a', 'c0000000-0000-0000-0000-00000000002b')),
  'both arrival orders end deleted'
);

-- ---------------------------------------------------------------------------------------------
-- 4. A re-delivered event is inserted once, not twice, and is not an error. Events union.
-- ---------------------------------------------------------------------------------------------
select lives_ok(
  $$ select pg_temp.push('11111111-1111-1111-1111-111111111111',
       pg_temp.change('c0000000-0000-0000-0000-00000000003a', '2026-03-01T10:00:00Z', 'device-a', false, null,
         jsonb_build_array(pg_temp.event('c0000000-0000-0000-0000-00000000003a', 'e1', 0)))) $$,
  'first delivery of an event is stored'
);
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111',
    pg_temp.change('c0000000-0000-0000-0000-00000000003a', '2026-03-01T10:00:00Z', 'device-a', false, null,
      jsonb_build_array(pg_temp.event('c0000000-0000-0000-0000-00000000003a', 'e1', 0)))),
  '{"acceptedIds": ["c0000000-0000-0000-0000-00000000003a"], "rejectedIds": []}'::jsonb,
  'replaying an applied change is accepted as a no-op'
);
select is(
  (select count(*) from public.study_session_events where session_id = 'c0000000-0000-0000-0000-00000000003a')::int,
  1,
  'a re-delivered event is stored once'
);
select is(
  (select wall_clock from public.study_session_events
     where session_id = 'c0000000-0000-0000-0000-00000000003a' and id = 'e1'),
  '2026-03-01 09:00:00+00'::timestamptz,
  'the stored event is the first delivery'
);
-- A second device holding the other half of the log, with older metadata: its metadata loses but
-- its events are unioned in, and the rewritten wall clock of the shared event is ignored.
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111',
    pg_temp.change('c0000000-0000-0000-0000-00000000003a', '2026-03-01T09:59:00Z', 'device-b', false, 'older',
      jsonb_build_array(
        jsonb_set(pg_temp.event('c0000000-0000-0000-0000-00000000003a', 'e1', 0), '{wallClock}', '"2030-01-01T00:00:00Z"'),
        pg_temp.event('c0000000-0000-0000-0000-00000000003a', 'e2', 1)))),
  '{"acceptedIds": [], "rejectedIds": ["c0000000-0000-0000-0000-00000000003a"]}'::jsonb,
  'older metadata carrying new events is still rejected as stale'
);
select is(
  (select array_agg(id order by sequence, id collate "C") from public.study_session_events
    where session_id = 'c0000000-0000-0000-0000-00000000003a'),
  array['e1', 'e2'],
  'two halves of one log end up unioned by id'
);
select is(
  (select wall_clock from public.study_session_events
     where session_id = 'c0000000-0000-0000-0000-00000000003a' and id = 'e1'),
  '2026-03-01 09:00:00+00'::timestamptz,
  'an event is never overwritten by a later delivery'
);
select is(pg_temp.state('c0000000-0000-0000-0000-00000000003a'), 'device-a||2026-03-01T10:00:00.000000Z|false',
  'the losing metadata did not change the row');

-- ---------------------------------------------------------------------------------------------
-- 5. ADR 0012's three-way case: a stale push is rejected, not an error, and a deleted session
--    stays deleted across a second round trip. An explicit newer restore does bring it back.
-- ---------------------------------------------------------------------------------------------
select pg_temp.push('11111111-1111-1111-1111-111111111111',
  pg_temp.change('c0000000-0000-0000-0000-00000000004a', '2026-03-01T10:00:20Z', 'device-b', false, 'B edit'));
select pg_temp.push('11111111-1111-1111-1111-111111111111',
  pg_temp.change('c0000000-0000-0000-0000-00000000004a', '2026-03-01T10:00:30Z', 'server', true, 'B edit'));
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111',
    pg_temp.change('c0000000-0000-0000-0000-00000000004a', '2026-03-01T10:00:10Z', 'device-a', false, 'A offline note')),
  '{"acceptedIds": [], "rejectedIds": ["c0000000-0000-0000-0000-00000000004a"]}'::jsonb,
  'an offline edit older than a tombstone is rejected'
);
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111',
    pg_temp.change('c0000000-0000-0000-0000-00000000004a', '2026-03-01T10:00:10Z', 'device-a', false, 'A offline note')),
  '{"acceptedIds": [], "rejectedIds": ["c0000000-0000-0000-0000-00000000004a"]}'::jsonb,
  'the same stale edit is rejected again on a second round trip'
);
select is(pg_temp.state('c0000000-0000-0000-0000-00000000004a'), 'server|B edit|2026-03-01T10:00:30.000000Z|true',
  'the session stays deleted; it is not resurrected');
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111',
    pg_temp.change('c0000000-0000-0000-0000-00000000004a', '2026-03-01T10:00:40Z', 'device-a', false, 'restored')),
  '{"acceptedIds": ["c0000000-0000-0000-0000-00000000004a"], "rejectedIds": []}'::jsonb,
  'an explicit restore newer than the delete is accepted'
);
select is(pg_temp.state('c0000000-0000-0000-0000-00000000004a'), 'device-a|restored|2026-03-01T10:00:40.000000Z|false',
  'deletion is final but not irreversible');

-- A batch reports each change on its own.
select is(
  pg_temp.push('11111111-1111-1111-1111-111111111111',
    pg_temp.change('c0000000-0000-0000-0000-00000000004a', '2026-03-01T10:00:00Z', 'device-a'),
    pg_temp.change('c0000000-0000-0000-0000-00000000004b', '2026-03-01T10:00:00Z', 'device-a')),
  '{"acceptedIds": ["c0000000-0000-0000-0000-00000000004b"], "rejectedIds": ["c0000000-0000-0000-0000-00000000004a"]}'::jsonb,
  'a mixed batch reports a per-entity outcome'
);

-- ---------------------------------------------------------------------------------------------
-- 6. Delta pull: total, resumable order. A pull resumed from a mid-stream cursor returns no
--    duplicates and no gaps, even when a row already read changes in between.
-- ---------------------------------------------------------------------------------------------
select is(
  (select count(*) from public.sync_pull_study_sessions('22222222-2222-2222-2222-222222222222', 0, 100))::int,
  0,
  'bob starts with an empty delta'
);
select pg_temp.push('22222222-2222-2222-2222-222222222222',
  pg_temp.change('d0000000-0000-0000-0000-000000000001', '2026-03-01T10:00:00Z', 'device-a'),
  pg_temp.change('d0000000-0000-0000-0000-000000000002', '2026-03-01T10:00:00Z', 'device-a'),
  pg_temp.change('d0000000-0000-0000-0000-000000000003', '2026-03-01T10:00:00Z', 'device-a'),
  pg_temp.change('d0000000-0000-0000-0000-000000000004', '2026-03-01T10:00:00Z', 'device-a'),
  pg_temp.change('d0000000-0000-0000-0000-000000000005', '2026-03-01T10:00:00Z', 'device-a'));

create temp table page_one as
  select * from public.sync_pull_study_sessions('22222222-2222-2222-2222-222222222222', 0, 2);
select is(
  (select array_agg(session->>'id' order by change_seq) from page_one),
  array['d0000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000002'],
  'the first page holds the two oldest changes, even though every updatedAt is identical'
);

-- Mid-stream: one already-read row is edited, and an offline device lands an *older* edit on a
-- row not yet read. Neither may be skipped.
select pg_temp.push('22222222-2222-2222-2222-222222222222',
  pg_temp.change('d0000000-0000-0000-0000-000000000001', '2026-03-01T11:00:00Z', 'device-a', false, 'edited'));
select pg_temp.push('22222222-2222-2222-2222-222222222222',
  pg_temp.change('d0000000-0000-0000-0000-000000000006', '2026-02-01T10:00:00Z', 'device-offline'));

create temp table page_rest as
  select * from public.sync_pull_study_sessions(
    '22222222-2222-2222-2222-222222222222', (select max(change_seq) from page_one), 100);
select is(
  (select array_agg(session->>'id' order by change_seq) from page_rest),
  array['d0000000-0000-0000-0000-000000000003', 'd0000000-0000-0000-0000-000000000004',
        'd0000000-0000-0000-0000-000000000005', 'd0000000-0000-0000-0000-000000000001',
        'd0000000-0000-0000-0000-000000000006'],
  'the resumed pull has no gap: unread rows, then the re-edited row, then the late older edit'
);
select is(
  (select count(*) from page_rest)::int,
  (select count(distinct session->>'id') from page_rest)::int,
  'the resumed pull has no duplicates'
);
select ok(
  (select min(change_seq) from page_rest) > (select max(change_seq) from page_one),
  'the resumed pull starts strictly after the cursor'
);
select is(
  (select count(*) from public.sync_pull_study_sessions(
    '22222222-2222-2222-2222-222222222222', (select max(change_seq) from page_rest), 100))::int,
  0,
  'a pull from the latest cursor is empty'
);

-- ---------------------------------------------------------------------------------------------
-- 7. The wire projection, and ADR 0017: no uptime_millis or boot_id is stored or returned.
-- ---------------------------------------------------------------------------------------------
select hasnt_column('public', 'study_session_events', 'uptime_millis', 'events have no uptime_millis column');
select hasnt_column('public', 'study_session_events', 'boot_id', 'events have no boot_id column');
select pg_temp.push('22222222-2222-2222-2222-222222222222',
  pg_temp.change('d0000000-0000-0000-0000-000000000007', '2026-03-01T10:00:00.123Z', 'device-a', false, 'wire',
    jsonb_build_array(
      pg_temp.event('d0000000-0000-0000-0000-000000000007', 'z', 1)
        || '{"uptimeMillis": 123456, "bootId": "boot-secret"}'::jsonb,
      pg_temp.event('d0000000-0000-0000-0000-000000000007', 'b', 1),
      pg_temp.event('d0000000-0000-0000-0000-000000000007', 'Y', 0))));
create temp table wire as
  select session from public.sync_pull_study_sessions('22222222-2222-2222-2222-222222222222', 0, 100)
  where session->>'id' = 'd0000000-0000-0000-0000-000000000007';
select ok(
  not (select session::text from wire) ~ '(uptimeMillis|bootId|boot-secret|123456)',
  'a returned session carries no device-local anchor'
);
select is(
  (select array_agg(e.value->>'id' order by e.position)
    from wire, jsonb_array_elements(session->'events') with ordinality as e(value, position)),
  array['Y', 'b', 'z'],
  'events are listed in (sequence, id) order, id compared by code point'
);
select is(
  (select session - 'events' from wire),
  jsonb_build_object(
    'id', 'd0000000-0000-0000-0000-000000000007', 'deviceId', 'device-a',
    'updatedAt', '2026-03-01T10:00:00.123000Z', 'startedAt', '2026-03-01T09:00:00.000000Z',
    'endedAt', '2026-03-01T10:00:00.000000Z', 'status', 'STOPPED', 'note', 'wire',
    'deleted', false, 'manualOverride', false, 'countedMillis', 3600000, 'unverifiedMillis', 1500),
  'the projection matches SyncSessionDto field for field'
);
select is(
  (select session->'events'->0 from wire),
  '{"id": "Y", "sessionId": "d0000000-0000-0000-0000-000000000007", "type": "STARTED", "sequence": 0, "wallClock": "2026-03-01T09:00:00.000000Z"}'::jsonb,
  'an event carries exactly the replicated fields'
);

-- ---------------------------------------------------------------------------------------------
-- 8. Ownership: the owner argument is the only scope, and ids belonging to another account are
--    never written to, unioned into, or read.
-- ---------------------------------------------------------------------------------------------
select is(
  pg_temp.push('22222222-2222-2222-2222-222222222222',
    pg_temp.change('c0000000-0000-0000-0000-00000000000a', '2030-01-01T00:00:00Z', 'zzz', true, 'hijack',
      jsonb_build_array(pg_temp.event('c0000000-0000-0000-0000-00000000000a', 'bob-event', 0)))),
  '{"acceptedIds": [], "rejectedIds": ["c0000000-0000-0000-0000-00000000000a"]}'::jsonb,
  'bob cannot overwrite alice''s session by pushing its id'
);
select is(pg_temp.state('c0000000-0000-0000-0000-00000000000a'), 'device-b|from B|2026-03-01T10:00:20.000000Z|false',
  'alice''s session is unchanged');
select is(
  (select count(*) from public.study_session_events where id = 'bob-event')::int, 0,
  'bob cannot inject events into alice''s session'
);
select ok(
  not exists (
    select 1 from public.sync_pull_study_sessions('22222222-2222-2222-2222-222222222222', 0, 1000)
    where session->>'id' like 'c0000000%'
  ),
  'bob''s delta contains none of alice''s sessions'
);

insert into public.subjects (id, user_id, name, color_argb, device_id) values
  ('e1111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 'Alice subject', 0, 'device-a');
select pg_temp.push('22222222-2222-2222-2222-222222222222',
  pg_temp.change('d0000000-0000-0000-0000-000000000008', '2026-03-01T10:00:00Z', 'device-a')
    || '{"subjectId": "e1111111-1111-1111-1111-111111111111", "taskId": "not-a-uuid"}'::jsonb);
select is(
  (select concat_ws('|', coalesce(subject_id::text, 'null'), coalesce(task_id::text, 'null'))
    from public.study_sessions where id = 'd0000000-0000-0000-0000-000000000008'),
  'null|null',
  'a session cannot reference another user''s subject, and an unresolvable reference is dropped'
);
select pg_temp.push('11111111-1111-1111-1111-111111111111',
  pg_temp.change('c0000000-0000-0000-0000-00000000005a', '2026-03-01T10:00:00Z', 'device-a')
    || '{"subjectId": "e1111111-1111-1111-1111-111111111111"}'::jsonb);
select is(
  (select subject_id from public.study_sessions where id = 'c0000000-0000-0000-0000-00000000005a'),
  'e1111111-1111-1111-1111-111111111111'::uuid,
  'a session keeps a reference to its owner''s own subject'
);

-- Every write path advances the clock: a direct write, not only the push function.
select lives_ok(
  $$ update public.study_sessions set note = 'direct' where id = 'd0000000-0000-0000-0000-000000000002' $$,
  'a direct service-role write succeeds'
);
select is(
  (select session->>'id' from public.sync_pull_study_sessions('22222222-2222-2222-2222-222222222222', 0, 1000)
    order by change_seq desc limit 1),
  'd0000000-0000-0000-0000-000000000002',
  'a direct write moves the row to the head of the delta'
);

reset role;
set local role authenticated;
select ok(
  not has_function_privilege('authenticated', 'public.sync_push_study_sessions(uuid,jsonb)', 'execute'),
  'clients cannot invoke the service-role push for another user'
);
select ok(
  not has_function_privilege('authenticated', 'public.sync_pull_study_sessions(uuid,bigint,integer)', 'execute'),
  'clients cannot invoke the service-role pull for another user'
);
select ok(
  not has_table_privilege('authenticated', 'public.study_session_sync_clocks', 'update'),
  'clients cannot rewind or skip a sync clock'
);
reset role;

select * from finish();
rollback;
