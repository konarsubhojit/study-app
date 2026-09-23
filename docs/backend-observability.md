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
structured logs without exposing any user content.

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
  initial model uses $0.021/GiB-month storage and $0.09/GiB egress placeholders. Egress is estimated
  from signed download URL issuance because the BFF does not see the provider's actual transfer log;
  update the migration and this runbook when provider pricing or measured egress exports change.

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
