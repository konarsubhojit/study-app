-- Direct PostgREST accesses in storage and api, including best-effort observability writes.
-- Keep existing grants in the replayable manifest too, so local default privileges cannot hide
-- a missing grant in the service_role_privileges suite.
insert into public.service_role_table_grants (table_name, privilege, reason) values
  ('storage_uploads', 'select', 'storage: loadUpload, readyObject (stat/download), completeUpload and deleteObject read metadata'),
  ('storage_uploads', 'update', 'storage: initUpload, completeUpload, deleteObject and reapOrphans update upload state'),
  ('storage_url_audit', 'insert', 'storage: audit records presigned upload and download URLs'),
  ('backend_observability_events', 'insert', 'storage and api: recordObservability writes request metrics'),
  ('subjects', 'select', 'api: listSubjects reads the caller''s subjects'),
  ('account_deletion_receipts', 'select', 'api: wasAlreadyDeleted reads a receipt for an idempotent account deletion'),
  ('account_deletion_receipts', 'insert', 'api: deleteAccount records a receipt before deleting the auth user'),
  ('auth_signin_challenges', 'insert', 'api: storeChallenge stores sign-in and passkey registration challenges'),
  ('passkey_credentials', 'select', 'api: passkeyCredential and beginPasskeyRegistration read credentials'),
  ('passkey_credentials', 'insert', 'api: registerPasskey stores a verified credential'),
  ('passkey_credentials', 'update', 'api: signInWithPasskey updates the signature counter and last-used time')
on conflict (table_name, privilege) do nothing;

-- No direct INSERT on storage_uploads: storage_reserve_upload is security definer.
-- No direct DELETE: deleteObject tombstones metadata. Other storage tables are RPC-only.
-- Identity defaults on audit/observability inserts do not require sequence privileges.
select public.apply_service_role_table_grants();
