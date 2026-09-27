-- Pending authentication challenges issued by the api Edge Function for WebAuthn sign-in.
create table public.auth_signin_challenges (
  challenge_hash text primary key check (challenge_hash ~ '^[0-9a-f]{64}$'),
  request_json text not null,
  expires_at timestamptz not null,
  consumed_at timestamptz,
  created_at timestamptz not null default now(),
  check (consumed_at is null or consumed_at >= created_at)
);

comment on table public.auth_signin_challenges is
  'Short-lived, single-use WebAuthn sign-in challenges. Only the api Edge Function service role may create or consume rows.';

create index auth_signin_challenges_expiry_idx
  on public.auth_signin_challenges (expires_at)
  where consumed_at is null;

alter table public.auth_signin_challenges enable row level security;

revoke all on public.auth_signin_challenges from anon, authenticated;
grant select, insert, update, delete on public.auth_signin_challenges to service_role;

alter table public.backend_observability_events
  drop constraint backend_observability_events_operation_check;
alter table public.backend_observability_events
  add constraint backend_observability_events_operation_check
  check (operation in (
    'initUpload',
    'completeUpload',
    'getDownloadUrl',
    'delete',
    'reapOrphans',
    'beginSignIn',
    'signIn',
    'refreshTokens',
    'unknown'
  ));
