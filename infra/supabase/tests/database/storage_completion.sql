begin;
create extension if not exists pgtap with schema extensions;
create extension if not exists dblink with schema extensions;
select plan(22);

insert into auth.users (id, email) values
  ('33333333-3333-4333-8333-333333333333', 'completion@example.com');

set local role service_role;
create temporary table reservation as
  select * from public.storage_reserve_upload(
    '33333333-3333-4333-8333-333333333333',
    '33333333-3333-4333-8333-333333333333/' || repeat('a', 64),
    repeat('a', 64), 'application/pdf', 12, '["checksum"]'::jsonb, now() + interval '24 hours'
  );
update public.storage_uploads set provider_upload_id = 'provider-1'
  where id = (select upload_id from reservation);
create temporary table claims as
  select public.storage_claim_completion(
    (select upload_id from reservation), '33333333-3333-4333-8333-333333333333'
  ) as first_claim;

select ok((select first_claim is not null from claims), 'a pending upload has one completion owner');
select is(
  public.storage_claim_completion((select upload_id from reservation), '33333333-3333-4333-8333-333333333333'),
  null::timestamptz, 'another completion cannot bypass the active claim'
);
select throws_ok(
  $$select * from public.storage_reserve_upload(
    '33333333-3333-4333-8333-333333333333',
    '33333333-3333-4333-8333-333333333333/' || repeat('a', 64),
    repeat('a', 64), 'application/pdf', 12, '["checksum"]'::jsonb, now() + interval '24 hours')$$,
  'P0001', 'upload_in_progress', 'initUpload reports the same in-progress conflict as stat'
);

update public.storage_uploads set expires_at = now() - interval '1 minute'
  where id = (select upload_id from reservation);
select is(
  (select count(*) from public.storage_claim_expired_uploads(100)), 0::bigint,
  'the upload session expiry cannot reap a live completion'
);
update public.storage_uploads set completing_at = now() - interval '14 minutes'
  where id = (select upload_id from reservation);
select is(
  public.storage_claim_completion((select upload_id from reservation), '33333333-3333-4333-8333-333333333333'),
  null::timestamptz, 'a slow completion keeps its claim for the full fifteen minutes'
);
update public.storage_uploads set completing_at = now() - interval '16 minutes'
  where id = (select upload_id from reservation);
create temporary table recovered as
  select * from public.storage_reserve_upload(
    '33333333-3333-4333-8333-333333333333',
    '33333333-3333-4333-8333-333333333333/' || repeat('a', 64),
    repeat('a', 64), 'application/pdf', 12, '["checksum"]'::jsonb, now() + interval '24 hours'
  );
select is((select upload_id from recovered), (select upload_id from reservation), 'recovery retains the original upload');
select is((select created from recovered), false, 'recovery does not create another multipart upload');
select is((select provider_upload_id from recovered), 'provider-1', 'provider success can be recovered by HEAD');
select is((select state from public.storage_uploads where id = (select upload_id from reservation)), 'pending',
  'a stranded claim is reservable again');
select ok((select expires_at > now() from recovered), 'a recovered session has time to retry');
select is(public.storage_remaining_quota('33333333-3333-4333-8333-333333333333'), 1073741812::bigint,
  'recovery reserves quota exactly once');
alter table claims add column second_claim timestamptz;
update claims set second_claim = public.storage_claim_completion(
  (select upload_id from reservation), '33333333-3333-4333-8333-333333333333'
);
select ok((select second_claim is not null and second_claim <> first_claim from claims), 'recovery gets a fresh fence');
select is(public.storage_finalize_upload(
  (select upload_id from reservation), '33333333-3333-4333-8333-333333333333', (select first_claim from claims)),
  false, 'the old worker cannot finalize a new claim');
with released as (
    update public.storage_uploads set state = 'pending'
      where id = (select upload_id from reservation) and state = 'completing'
        and completing_at = (select first_claim from claims)
      returning id
)
select is((select count(*) from released), 0::bigint, 'the old worker cannot release a new claim');
select is(public.storage_finalize_upload(
  (select upload_id from reservation), '33333333-3333-4333-8333-333333333333', (select second_claim from claims)),
  true, 'the current owner can finalize');
select is((select count(*) from public.thumbnail_generation_queue where upload_id = (select upload_id from reservation)),
  1::bigint, 'finalization schedules thumbnails once');
select throws_ok(
  $$select * from public.storage_reserve_upload(
    '33333333-3333-4333-8333-333333333333',
    '33333333-3333-4333-8333-333333333333/' || repeat('a', 64),
    repeat('a', 64), 'application/pdf', 12, '["checksum"]'::jsonb, now() + interval '24 hours')$$,
  'P0001', 'object_already_exists', 'only a ready object yields the permanent existing-object conflict'
);
reset role;
select ok(not has_function_privilege('authenticated', 'public.storage_claim_completion(uuid,uuid)', 'execute'),
  'clients cannot claim completion directly');
select ok(not has_function_privilege('authenticated', 'public.storage_finalize_upload(uuid,uuid,timestamptz)', 'execute'),
  'clients cannot finalize completion directly');

-- Real concurrent transactions: neither can see the other's claim until the row lock is released.
-- Use a separate committed fixture because this suite's transaction is rolled back.
-- The local Supabase test database uses its role name as the default password; no cloud
-- credentials belong in this suite.
select dblink_connect('completion_setup',
  format('host=%s dbname=%I user=%I %s=%L', inet_server_addr(), current_database(), current_user, 'password', current_user));
select dblink_exec('completion_setup',
  $$insert into auth.users(id, email) values ('44444444-4444-4444-8444-444444444444', 'concurrent-completion@example.com')$$);
select dblink_exec('completion_setup',
  $$insert into public.storage_uploads(id, user_id, object_key, content_hash, content_type, size_bytes, part_checksums, expires_at)
    values ('55555555-5555-4555-8555-555555555555', '44444444-4444-4444-8444-444444444444',
      '44444444-4444-4444-8444-444444444444/' || repeat('b', 64), repeat('b', 64),
      'application/pdf', 12, '["checksum"]', now() + interval '24 hours')$$);
select dblink_connect('completion_a',
  format('host=%s dbname=%I user=%I %s=%L', inet_server_addr(), current_database(), current_user, 'password', current_user));
select dblink_connect('completion_b',
  format('host=%s dbname=%I user=%I %s=%L', inet_server_addr(), current_database(), current_user, 'password', current_user));
select dblink_exec('completion_setup', 'begin');
select dblink_exec('completion_setup',
  $$update public.storage_uploads set provider_upload_id = 'provider-2'
    where id = '55555555-5555-4555-8555-555555555555'$$);
select dblink_send_query('completion_a',
  $$select public.storage_claim_completion('55555555-5555-4555-8555-555555555555', '44444444-4444-4444-8444-444444444444')$$);
select dblink_send_query('completion_b',
  $$select public.storage_claim_completion('55555555-5555-4555-8555-555555555555', '44444444-4444-4444-8444-444444444444')$$);
select dblink_exec('completion_setup', 'commit');
create temporary table concurrent_claims as
  select * from dblink_get_result('completion_a') as result(claim timestamptz)
  union all
  select * from dblink_get_result('completion_b') as result(claim timestamptz);
select is((select count(*) from concurrent_claims), 2::bigint, 'both concurrent completions returned');
select is((select count(claim) from concurrent_claims), 1::bigint, 'exactly one concurrent completion wins');
select is(public.storage_claim_completion(
  '55555555-5555-4555-8555-555555555555', '33333333-3333-4333-8333-333333333333'),
  null::timestamptz, 'a different owner cannot take the claim');
select dblink_exec('completion_setup',
  $$delete from auth.users where id = '44444444-4444-4444-8444-444444444444'$$);
select dblink_disconnect('completion_a');
select dblink_disconnect('completion_b');
select dblink_disconnect('completion_setup');
select * from finish();
rollback;
