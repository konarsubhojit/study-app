-- Keep only a digest of the deleting session so a retry after Auth revokes its JWT returns 404.
create table public.account_deletion_receipts (
  token_hash text primary key check (token_hash ~ '^[0-9a-f]{64}$'),
  user_id uuid not null,
  created_at timestamptz not null default now()
);

alter table public.account_deletion_receipts enable row level security;
revoke all on public.account_deletion_receipts from anon, authenticated;
grant select, insert on public.account_deletion_receipts to service_role;

alter table public.backend_observability_events
  drop constraint backend_observability_events_operation_check;
alter table public.backend_observability_events
  add constraint backend_observability_events_operation_check
  check (operation in (
    'initUpload', 'completeUpload', 'getDownloadUrl', 'delete', 'reapOrphans',
    'beginSignIn', 'signIn', 'refreshTokens', 'deleteAccount', 'unknown'
  ));
