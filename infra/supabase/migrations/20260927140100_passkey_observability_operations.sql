-- Telemetry for the passkey routes. The operation column is a closed vocabulary, so a new route
-- silently loses its telemetry (the write is dropped by the check constraint) until it is listed
-- here alongside the existing operations.
alter table public.backend_observability_events
  drop constraint backend_observability_events_operation_check;
alter table public.backend_observability_events
  add constraint backend_observability_events_operation_check
  check (operation in (
    'initUpload', 'completeUpload', 'getDownloadUrl', 'delete', 'reapOrphans',
    'beginSignIn', 'signIn', 'refreshTokens', 'listSubjects', 'listTasks',
    'deleteAccount', 'pullSessionChanges', 'pushSessionChanges',
    'beginPasskeyRegistration', 'completePasskeyRegistration', 'unknown'
  ));
