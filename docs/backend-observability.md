# Backend observability, backups and cost guardrails

The backend is observable without logging student content. The storage Edge Function emits one
structured event per request and mirrors the same safe fields into
`public.backend_observability_events`: request id, operation, HTTP status, duration, error code and
egress bytes. It does **not** record bearer tokens, user ids, object keys, file names, request URLs or
presigned URLs.

## Dashboards

Provision the production dashboard from these SQL views after every migration:

| Panel | Source | Alert policy |
| --- | --- | --- |
| Error rate | `backend_health_daily.error_rate` | `backend_alert_policies.metric = 'error_rate'` |
| p95 latency | `backend_health_daily.p95_latency_ms` | `backend_alert_policies.metric = 'p95_latency_ms'` |
| Upload success rate | `backend_health_daily.upload_success_rate` | `backend_alert_policies.metric = 'upload_success_rate'` |
| Storage growth | `backend_storage_growth_daily.ready_bytes_added` | `backend_alert_policies.metric = 'storage_growth_gib_daily'` |
| Egress volume estimate | `backend_health_daily.egress_bytes` | `backend_alert_policies.metric = 'egress_gib_daily'` |
| Auth failures | `backend_health_daily.auth_failures` | `backend_alert_policies.metric = 'auth_failures'` |
| Cost per 1,000 active users | `backend_cost_projection.projected_monthly_usd_per_1000_active_users` | ops review when reality differs by more than 20% |

The same request id is returned as `x-request-id`, so a support report can be correlated with the
structured logs without exposing any user content. `error_code` is nullable and populated for errors
raised by the BFF; `status` remains the alerting source of truth for every response.

## Alert response

1. Confirm the alert reached a human: acknowledge it in the alerting tool and copy the incident id
   into the release notes or incident log.
2. Query `backend_health_daily` for the affected day and inspect the safe structured logs by
   `request_id`.
3. If error rate or p95 latency is elevated, pause risky deploys, check Supabase status, then inspect
   the storage provider for throttling or regional errors.
4. If upload success drops, test `initUpload` and `completeUpload` with a small PDF using a non-prod
   account and verify the orphan reaper is still running.
5. Resolve, write a short post-incident note, and update `backend_alert_policies` if the threshold
   was noisy or too slow.

### Induced-failure drill

Quarterly, deploy a staging-only `STORAGE_S3_ENDPOINT` that rejects requests, call `initUpload`, and
verify the `error_rate` alert routes to `primary-on-call`. Revert the setting immediately after the
alert is acknowledged.

### Edge Function database permissions and deployment

Unmatched failures of the functions' own upstream queries, including `42501`, `42P01` and
`PGRST202`, return retryable `503 storage_unavailable` (storage) or `503 api_unavailable` (api),
not `400 invalid_request`. Explicit client errors such as `rate_limited` (429),
`quota_exceeded` (413) and `object_already_exists` (409) retain their existing handling.
The storage diagnostic records only the bounded database code, resource and status, never
upstream details or request bodies.

This fix requires **both** deployments from `infra/`:

```sh
supabase db push
supabase functions deploy storage api
```

Merging alone, or deploying only the functions, does not apply the table grants. The migration
extends `service_role_table_grants`, whose privileges CI replays after stripping local default
grants. The direct-access inventory is:

| Table | Privileges | Edge Function code paths | Grant status |
| --- | --- | --- | --- |
| `storage_uploads` | SELECT | storage `loadUpload`, `readyObject` (stat/download), `completeUpload`, `deleteObject` | New |
| `storage_uploads` | UPDATE | storage `initUpload`, `completeUpload`, `deleteObject`, `reapOrphans`, failure cleanup | New |
| `storage_url_audit` | INSERT | storage `audit` for upload-part/download URLs | New |
| `backend_observability_events` | INSERT | both functions' `recordObservability` | Existing, now replay-tested |
| `subjects` | SELECT | api `listSubjects` | Existing and already in manifest |
| `account_deletion_receipts` | SELECT, INSERT | api `wasAlreadyDeleted`, `deleteAccount` respectively | Existing, now replay-tested |
| `auth_signin_challenges` | INSERT | api `storeChallenge` for sign-in/registration | Existing, now replay-tested |
| `passkey_credentials` | SELECT | api `passkeyCredential`, `beginPasskeyRegistration` | Existing, now replay-tested |
| `passkey_credentials` | INSERT | api `registerPasskey` | Existing, now replay-tested |
| `passkey_credentials` | UPDATE | api `signInWithPasskey` | Existing, now replay-tested |

No direct INSERT privilege is needed on `storage_uploads`: reservation inserts run in the
security-definer `storage_reserve_upload` RPC. Deletion tombstones rows, so no DELETE grant is
needed. `storage_accounts`, `storage_rate_events` and `thumbnail_generation_queue` are accessed
only by security-definer RPCs. Identity defaults for the two append-only logs do not need sequence
grants. RLS remains enabled and unchanged.

## Multipart completion transport and recovery

The reported hosted crash occurs in Deno's Node-compatible HTTP response reader
(`IncomingMessageForClient._read`, `TypeError: reached unexpected EOF`), before the request catch
or observability finally runs. Storage now explicitly uses Smithy's native-fetch request handler,
with a 60-second request timeout and at most two SDK attempts. Response-body failures are awaited
by the SDK and reach `503 storage_unavailable`, the request trace header, and `storage_request`.
All function-owned asynchronous work is awaited or rejection-handled, including background
observability writes. A global `error`/`unhandledrejection` listener is intentionally not used:
it cannot associate an arbitrary worker failure with one of several concurrent requests or
manufacture that request's response, and suppressing it would hide bugs rather than recover them.

Diagnosis evidence and limits:

- `aws s3 cp` with a 9 MB fixture, `--checksum-algorithm SHA256`, and the supplied endpoint/region
  failed with `Unable to locate credentials`. No authenticated endpoint request was made.
  The underlying hosted endpoint trigger remains **unconfirmed**.
- A controlled HTTP response declaring 500 bytes but closing after
  `<CompleteMultipartUploadResult>` was tested against SDK 3.1135.0 on Deno 2.9.6. Both default
  Node transport and native fetch reached catch/finally (respectively `Error` and `TypeError`).
  Thus a generic short response alone did not reproduce the reported deployed-runtime crash.
- The regression suite drives the actual SDK through native fetch with a failing response stream
  and proves a structured, logged outage without an unhandled rejection. It also pins the creation
  checksum header, signed-part checksum, and exact completion XML.
- A manual call of the actual function against a local TCP server with that truncated response
  returned traced `503 storage_unavailable` and logged `storage_request` in 155 ms; no worker crash
  or unhandled rejection occurred.
- [Supabase's compatibility table](https://supabase.com/docs/guides/storage/s3/compatibility)
  lists `CreateMultipartUpload`, `UploadPart`, and `CompleteMultipartUpload` as supported.
  It does not explicitly settle SHA-256 checksums on these multipart operations. Unsupported
  checksum entries on other operations are not evidence that these calls caused this crash.
  Checksum incompatibility, endpoint-specific framing, and a deployed Deno bug have therefore
  **not** been ruled out.

SHA-256 is retained consistently on creation, part signing, and completion; no integrity guarantee
is removed speculatively. The server additionally checks provider `ContentLength` against
`size_bytes`. The device's VERIFY compares the returned hash and size with its local plan, not a
fresh digest of remote bytes; without a configured scan hook, that comparison alone would not
replace provider checksum validation. Before rollout verification, run the authenticated CLI
check with a file larger than the CLI multipart threshold, then compare function completion on
the same endpoint. Do not include credentials, object keys, or signed URLs in diagnostic logs.

Completion ownership now has a **15-minute lease**, separate from the 24-hour upload-session
expiry. This exceeds Supabase's hosted Edge worker wall-clock limits (including the 400-second
paid-plan limit), with margin for a slow 50 MB provider completion and SDK retries. Active claims
cannot be bypassed by a second completion or reaped merely because the upload session expired.
An expired claim can be acquired again or reclaimed by reservation, retaining the same upload id
and provider handle. Recovery checks HEAD first because S3 may have succeeded before the response
was lost. Finalization and failure release are fenced by the claim timestamp, so an old worker
cannot overwrite a newer claim. Failures return to pending, except confirmed integrity/scan
rejections after provider deletion; cleanup failure retains the lease for timeout recovery.

`stat` reports active `completing`/`reaping` rows as `409 upload_in_progress`, matching reservation.
A reclaimable completion reads as absent and can be reserved again. Only explicitly recoverable
conflicts (`upload_initializing`, `upload_in_progress`) are automatically retried by the client;
`object_already_exists`, incompatible reservations and unknown conflicts are non-retryable.

Rollout requires **both** commands from `infra/`, database first:

```sh
supabase db push
supabase functions deploy storage api
```

The migration changes the finalization RPC signature; deploy the functions immediately after the
database. Merged-but-undeployed code, or deploying only one side, is not a valid verification.
The new SDK wire tests require Deno's narrowly scoped `--allow-sys=osRelease` permission in
addition to `--allow-env`; no network permission or real provider credentials are needed.

## Cost guardrails

- **Per-account quota:** `storage_accounts.quota_bytes` defaults to 1 GiB, and
  `storage_reserve_upload` rejects reservations that exceed the remaining quota.
- **Orphan expiry:** `.github/workflows/reap-storage-orphans.yml` calls `reapOrphans` hourly; the
  function aborts expired multipart uploads and marks stale metadata failed.
- **Cold tiering:** configure the storage provider lifecycle policy to transition ready material
  objects after the `cold-tier-ready-materials` interval in `storage_lifecycle_rules`.
- **Metadata expiry:** failed/deleted upload metadata is retained for the
  `failed-or-deleted-metadata-expiry` review window in `storage_lifecycle_rules`, then can be purged
  by maintenance.
- **Budget review:** compare the provider invoice with
  `backend_cost_projection.projected_monthly_usd_per_1000_active_users` every billing cycle. The
  initial `backend_cost_assumptions` rows use $0.021/GiB-month storage and $0.09/GiB egress
  placeholders. Egress is estimated from signed download URL issuance because the BFF does not see
  the provider's actual transfer log; update the assumption rows and this runbook when provider
  pricing or measured egress exports change.

## Abuse response plan

1. Identify the signal: auth failures, egress spike, quota exhaustion, or repeated rate limiting.
2. Preserve only operational evidence: request ids, status/error codes, aggregate bytes and times.
   Do not export presigned URLs, object keys or file names.
3. Temporarily lower `storage_accounts.quota_bytes` for the abusive account or disable the account in
   Supabase Auth.
4. Rotate `ORPHAN_REAPER_TOKEN` or storage credentials if a server-side secret is suspected.
5. After containment, restore normal quotas only after egress and auth-failure panels return to
   baseline.

## Backup and restore drill

Database recovery is migration-first because Supabase stores the backend in plain Postgres.

1. Take a managed Supabase backup or `pg_dump` before each production migration.
2. Restore into a clean staging project.
3. Apply repository migrations with `supabase db push`.
4. Run `infra/scripts/test.sh` against staging or `supabase test db --local` for local verification.
5. Spot-check `backend_health_daily`, `backend_cost_projection` and one restored user-owned table.
6. Record the drill date, backup id, restore target and test result in the incident log.

The repository-level restore procedure is tested by rebuilding a database from migrations and running
the pgTAP authorization suite; the production drill adds the managed backup id and human sign-off.
