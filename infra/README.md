# Infrastructure

Backend platform: [Supabase](https://supabase.com) (Postgres + Auth + Storage + Row Level
Security). See [ADR 0007 — backend platform](../docs/adr/0007-backend-platform.md) for why, the
cost model and the exit path, and [ADR 0005](../docs/adr/0005-object-storage.md) for the client
upload contract this backend serves.

Everything under [`supabase/`](supabase) is infrastructure-as-code: the schema, RLS policies and
authorisation tests are SQL files checked into this repository, applied the same way to every
environment. Nobody runs ad hoc SQL against dev or prod; a schema change is a new migration file in
a pull request.

```
infra/
  supabase/
    config.toml            # project settings (ports, auth providers, studio, etc.)
    migrations/            # numbered SQL migrations — the schema, in order, from scratch
    tests/database/         # pgTAP authorisation tests
  scripts/
    dev-up.sh               # start the full local stack
    dev-down.sh              # stop it
    test.sh                   # run the authorisation test suite locally
    deploy-prod.sh           # push migrations to the linked prod project
    deploy-storage-function.sh # set secrets and deploy Edge Functions
```

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) — the local stack (Postgres, Auth, Storage,
  Realtime, Studio) runs as containers.
- [Supabase CLI](https://supabase.com/docs/guides/local-development/cli/getting-started) — install
  with `npm install -g supabase`, or any method on that page.
- [Deno](https://deno.com/) — runs the local Edge Function unit tests wired into
  `infra/scripts/test.sh`.

No Supabase account or cloud credential is needed for local development.

## Dev environment

Recreate the entire dev environment from scratch at any time:

```sh
cd infra
./scripts/dev-up.sh      # supabase start; applies every migration to a fresh Postgres
./scripts/test.sh        # runs Edge Function unit tests, then pgTAP authorisation tests
./scripts/dev-down.sh    # supabase stop; tears the stack down again
```

`dev-up.sh` always starts from a clean database (`supabase db reset` if the stack is already
running, `supabase start` otherwise), so "recreate from scratch" is a real, exercised code path, not
just a claim. Local credentials, URLs and ports are printed by `supabase status` after start; they
are locally-generated defaults, not secrets, and are never used against any other environment.

Studio (a local admin UI for browsing tables and running SQL) is available at
`http://127.0.0.1:54323` once the stack is up.

## Prod environment

Prod is a Supabase project linked to this repository, updated by pushing the same migrations that
were exercised locally and by the authorisation tests. The primary deployment path is the
manually-run **Deploy backend** GitHub Actions workflow; it works from a mobile browser and requires
no local machine, Docker, or Supabase CLI.

### GitHub Actions setup

Add the following repository Actions secrets for each operation.

#### Database deploy

| Secret | Purpose | Where to get it |
| --- | --- | --- |
| `SUPABASE_ACCESS_TOKEN` | Authenticates the CLI to the Supabase account | Supabase dashboard → Account → Access Tokens |
| `SUPABASE_DB_PASSWORD` | The target project's database password | Supabase dashboard → Project → Settings → Database |

#### Edge Function deploy

| Setting | Required? | Purpose | Where it comes from |
| --- | --- | --- | --- |
| `SUPABASE_ACCESS_TOKEN` | Required | Authenticates the CLI to the Supabase account | Same repository Actions secret used by the database deploy |
| `SUPABASE_URL` | Required, platform-provided | Project API URL used by the function | Supabase injects it automatically into hosted Edge Functions; do not add it as an Actions secret |
| `SUPABASE_SERVICE_ROLE_KEY` | Required, platform-provided | Server-only key used by the function | Supabase injects it automatically into hosted Edge Functions; do not add it as an Actions secret |
| `STORAGE_S3_ENDPOINT` | Required for `storage` and `api` | S3-compatible API endpoint | Storage provider dashboard |
| `STORAGE_S3_BUCKET` | Required for `storage` and `api` | Storage bucket name | Storage provider dashboard |
| `STORAGE_S3_ACCESS_KEY_ID` | Required for `storage` and `api` | Storage API access key | Storage provider dashboard |
| `STORAGE_S3_SECRET_ACCESS_KEY` | Required for `storage` and `api` | Storage API secret key | Storage provider dashboard |
| `ORPHAN_REAPER_TOKEN` | Required | Authenticates hourly orphan-reaper requests | Generate it yourself, for example with `openssl rand -hex 32` |
| `STORAGE_S3_REGION` | Optional | Storage region; defaults to `us-east-1` | Storage provider dashboard, when the provider requires a different region |
| `STORAGE_SCAN_HOOK_URL` | Optional | Malware scanner webhook | Malware scanner provider |
| `STORAGE_SCAN_HOOK_TOKEN` | Optional | Authenticates malware scanner requests | Malware scanner provider |
| `GOOGLE_SERVER_CLIENT_ID` | Required for `api` | Google OAuth server client ID accepted as the ID-token audience | Google Cloud Console OAuth client used by the Android Credential Manager flow |
| `API_MINIMUM_CLIENT_VERSION` | Required for `api` | Value sent in every `X-Minimum-Client-Version` response header | The oldest app version the backend currently supports, for example `1.0.0` |
| `API_BACKUP_RETENTION_DAYS` | Required for `api` | Maximum number of days before pre-deletion provider backups expire; must match enforced provider backup policies (use `0` only if no historical backups exist) | Database and object-storage backup settings |
| `PASSKEY_RP_ID` | Required for `api` | WebAuthn relying-party id the app's passkeys are bound to, for example `auth.example.com` (replace with your owned domain) | The domain hosting `assetlinks.json` for the Android app |
| `PASSKEY_ANDROID_ORIGIN` | Required for `api` | Comma-separated list of accepted `android:apk-key-hash:<base64url SHA-256 of the signing certificate>` origins | `keytool -list -v` on each signing certificate (upload key and Play release key), SHA-256 fingerprint re-encoded as base64url |

Except for the two platform-provided `SUPABASE_*` settings, add each setting that applies as a
repository Actions secret with the same name.

Passkey sign-in in the app is separately opt-in. It is off unless the
`STUDYFLOW_PASSKEY_SIGN_IN_ENABLED` repository **variable** is `true`, and `ci.yml` and
`release.yml` build with it. The `api` function still requires both `PASSKEY_*` secrets either way.
For the Google Cloud OAuth clients, `assetlinks.json`, and the fingerprint encodings these secrets
expect, see
[Google Cloud and Digital Asset Links setup](../docs/authentication.md#google-cloud-and-digital-asset-links-setup).

#### Hourly orphan reaper

| Secret | Purpose | Where to get it |
| --- | --- | --- |
| `STORAGE_REAPER_URL` | Base endpoint hit hourly by `reap-storage-orphans.yml` | After deploying the function, set it to `https://<project-ref>.supabase.co/functions/v1/storage` with no trailing `/reapOrphans` |
| `STORAGE_REAPER_TOKEN` | Authenticates hourly orphan-reaper requests | Use the exact same self-generated value as the function's `ORPHAN_REAPER_TOKEN` |

In GitHub, open **Repository → Settings → Secrets and variables → Actions → New repository
secret**, enter each name and value, then save it. `project_ref` is a workflow input, not a secret;
find it in the Supabase dashboard under **Project → Settings → General**. Adding secrets and running
the deploy both work from a mobile browser.

Storing `SUPABASE_ACCESS_TOKEN` and `SUPABASE_DB_PASSWORD` as repository Actions secrets is a
deliberate change from the previous credential guidance, which implied that credentials live only
in a password manager or external CI secret store. No credential, connection string, or project ref
is committed anywhere under `infra/`.

### Deploy with GitHub Actions

From the GitHub mobile or desktop web UI, open the **Actions** tab, select **Deploy backend**, choose
**Run workflow**, and tap **Run workflow**. It uses the `SUPABASE_PROJECT_REF` repository variable;
the project ref input is an optional override. Backend and Edge Function deploys to the same project
queue rather than interrupting one another. Any maintainer with write access can trigger this deploy.
`supabase db push --yes` runs noninteractively and is not reversible; if approval rules are wanted
later, add them to the `production` environment.

### Deploy locally

```sh
export SUPABASE_ACCESS_TOKEN=...   # from a managed secret store — never committed
export SUPABASE_DB_PASSWORD=...    # ditto
./infra/scripts/deploy-prod.sh <project-ref>
```

`deploy-prod.sh` only ever reads these from the environment; nothing under `infra/` contains a
prod credential, connection string or project ref. `<project-ref>` identifies which Supabase project
to link and push to, and is not a secret by itself, but is still passed as an argument rather than
hard-coded so the script can never be run against the wrong project by accident.

If Supabase were ever discontinued or became too expensive (see the cost model in
[ADR 0007 — backend platform](../docs/adr/0007-backend-platform.md)), the same
migrations apply as-is to any Postgres instance; only `deploy-prod.sh`'s use of `supabase db push`
against a linked *Supabase* project would need to change to a plain `psql` invocation against the
new host.

## Data model and authorisation

See [ADR 0007 — backend platform](../docs/adr/0007-backend-platform.md#data-model) for the table-by-table description.
Every user-owned table has an RLS policy tying every row to its owner
(`auth.uid() = user_id`); [`supabase/tests/database/authorization.sql`](supabase/tests/database/authorization.sql)
proves cross-user reads and writes affect zero rows for every table, run against the real
migrations rather than a hand-written approximation of them.

## Edge Functions

### API service

`supabase/functions/api` serves the versioned StudyFlow API contract under the single Supabase
Edge Function described by ADR 0017. The gateway leaves the `/functions/v1/api` prefix in the
request path, so `routedPath()` strips that prefix and the router matches the remaining path in
full. It deliberately does not dispatch on the final path segment the way the `storage` function
does: that approach cannot tell `/v1/sessions` from `/v1/sync/sessions`, since both end in
`sessions`. ADR 0017 requires full-path matching for exactly that reason, and
[`index_test.ts`](supabase/functions/api/index_test.ts) holds the router to it.
Authentication policy is declared per route inside the function.
The three `/v1/auth/signin`, `/v1/auth/signin/challenge` and `/v1/auth/refresh` routes are
unauthenticated by contract; every other route, passkey registration included, opts in to Supabase
JWT verification in that route table rather than relying on the gateway.

Every response includes `X-Minimum-Client-Version`, sourced from the `API_MINIMUM_CLIENT_VERSION`
function secret. Google sign-in verifies the ID token against the configured
`GOOGLE_SERVER_CLIENT_ID` audience and then asks Supabase Auth to mint the token pair.

That audience is one half of a pair. The app requests the ID token with the same value, supplied at
build time as `-Pstudyflow.googleServerClientId=...` (or `STUDYFLOW_GOOGLE_SERVER_CLIENT_ID`) and
surfaced as `BuildConfig.GOOGLE_SERVER_CLIENT_ID`. Both must be the Google Cloud OAuth **Web
application** client id — the Android client id is a different value and never validates. This is a
public identifier: it travels in every sign-in request and ships in the APK by design, so it is
build configuration rather than a secret. A mismatch has no distinctive symptom: the token is
minted happily by Google, audience validation rejects it here, and the user sees sign-in that
simply fails. If Google sign-in returns `401 invalid_credentials` for everyone while passkey
sign-in works, compare these two values first.

The CI testing APK (`ci.yml`) and the tagged release (`release.yml`) read the app's value from the
same `GOOGLE_SERVER_CLIENT_ID` repository secret the function is deployed with, so those builds
cannot drift from the server. A tagged release fails fast if the secret is missing. A local build
must pass the value itself. Without it the production flavour still builds, but `AuthModule` fails
to provide `GoogleSignInConfig`, with a message naming the missing property.

Google sign-in also needs an **Android** OAuth client for the package name and signing-certificate
SHA-1. Without it the Google option is silently dropped from the sheet. See
[Google Cloud OAuth clients](../docs/authentication.md#google-cloud-oauth-clients).

The app's base URL must point at this function — `https://<project-ref>.supabase.co/functions/v1/api`.
Production-flavour builds (debug and release) require backend configuration and fail during Gradle
configuration if it is missing. Pass `-Pstudyflow.supabaseProjectRef=<project-ref>`
(or set `STUDYFLOW_SUPABASE_PROJECT_REF`) to target a deployed project:

```bash
./gradlew installProductionDebug -Pstudyflow.supabaseProjectRef=<project-ref>
```

Both the CI testing APK and
tagged release read that ref from the `SUPABASE_PROJECT_REF` repository variable and fail if it is
missing; no project ref is committed. To target a local mock server, pass
both `-Pstudyflow.apiBaseUrl=http://10.0.2.2:8080` and
`-Pstudyflow.storageBaseUrl=http://10.0.2.2:54321/functions/v1/storage`.
Per-service overrides take precedence over the project ref. The project ref is not a secret: it appears in every
request URL. The gateway prefix stays in the path and the function strips it, so no client endpoint
path changes. The `mock` flavour gets no base URL at all and is served entirely by an in-process fake.

Neither credentials nor tokens are logged; request telemetry uses only `request_id`, `operation`,
`status`, `duration_ms`, `error_code`, and `egress_bytes`.

#### Passkeys

| Operation | Endpoint | Authentication |
| --- | --- | --- |
| `beginPasskeyRegistration` | `POST /v1/auth/passkey/registration/challenge` | Supabase JWT |
| `completePasskeyRegistration` | `POST /v1/auth/passkey/registration` | Supabase JWT |
| `signIn` (`{"type":"passkey"}`) | `POST /v1/auth/signin` | None, by contract |

Registration is authenticated because a passkey is attached to an account Google sign-in already
created; `docs/authentication.md` records that decision. Assertion and attestation verification is
delegated to `npm:@simplewebauthn/server`, pinned to an exact version.

WebAuthn verification is configured by two function secrets. `PASSKEY_RP_ID` is the relying-party
id every credential is bound to. `PASSKEY_ANDROID_ORIGIN` is the expected caller origin, and on
Android that is **not** a web origin: Credential Manager reports
`android:apk-key-hash:<base64url SHA-256 of the APK signing certificate>`. It accepts a
comma-separated list so a rollout signed with a different key than the installed build still
verifies.
The RP id must serve `https://<rp-id>/.well-known/assetlinks.json` and can never change once
passkeys exist. See
[Relying-party id and `assetlinks.json`](../docs/authentication.md#relying-party-id-and-assetlinksjson).

Misconfiguration presents in two distinct ways:

* A missing secret, a value that is not an `android:apk-key-hash:` origin, or an empty list, **fails
  the function at boot** (`Missing required server setting: ...` or
  `PASSKEY_ANDROID_ORIGIN must be a comma-separated list of ...`), so every route — Google sign-in
  and plain reads included — returns `500` with body `{"code":"WORKER_ERROR"}`. This is deliberate:
  a silently wrong origin would instead surface as universal sign-in failure long after anyone
  suspected the secret. `deploy-storage-function.sh` refuses to deploy `api` without both secrets.
* A syntactically valid but wrong `PASSKEY_RP_ID` or origin — the fingerprint of the other signing
  key, say — passes boot and then rejects **every** passkey assertion with
  `401 invalid_credentials`, while Google sign-in keeps working. A sudden all-passkey `401` rate
  with healthy Google sign-ins means these two secrets, not the client.

Registration and sign-in challenges are single-use rows in `auth_signin_challenges`, claimed by the
`consume_auth_challenge` function in one statement so a concurrent replay cannot observe an
unclaimed row. Credentials live in `passkey_credentials`, which has RLS enabled and no policy: the
service role is the only reader. Passkey sign-in mints its session through GoTrue's admin
magic-link route, generated and redeemed inside the function, because GoTrue exposes no admin
"create a session" endpoint.

**Account deletion is immediate, not deferred.** `DELETE /v1/account` authenticates the caller,
lists and removes all S3 objects and multipart uploads under their server-derived user prefix,
then deletes the Auth user. The profile and all user-owned rows cascade. The live account and
objects are removed before the `200` receipt; `retentionWindowDays` reports the separately
configured **maximum provider backup lifetime**, not a grace period or a delayed deletion job.
Operators must configure database and object-storage backup expiration at or before
`API_BACKUP_RETENTION_DAYS` and audit those policies before deployment; historical provider
snapshots cannot be individually erased by this endpoint. Set it to `0` only when there are no
pre-deletion backups. S3 credentials are supplied to `api` as function secrets as well as `storage`
because purging must complete before the cascading database delete loses its upload records.
S3 prefix listing also covers objects whose upload records were already lost. A failed purge
leaves the Auth user intact for retry. A repeat with the same revoked bearer token returns `404`
only when a recorded deletion attempt is confirmed absent in Auth; other invalid tokens return
`401`. The retry receipt table holds only a token digest and account id, never the token itself.

**Session sync (ADR 0012).** `GET /v1/sync/sessions` is the delta pull and `POST /v1/sync/sessions`
is the push. Both authenticate the caller and pass the JWT's owner to the service-role RPCs
`sync_pull_study_sessions` and `sync_push_study_sessions`, which filter explicitly by it.
Conflict resolution lives in the push RPC, under a row lock. It mirrors `SessionSyncMerge` rule for rule:

- only `STOPPED` sessions sync
- last-writer-wins on `updatedAt`
- an exact tie goes to the greater `deviceId` in code-point (`COLLATE "C"`) order, not the database locale, which orders `a`/`B` the other way
- a tombstone beats a live row on a full tie
- events are unioned by id and never overwritten

A change that loses comes back in `rejectedIds` with `200`; it is not an error.

The pull cursor is an opaque encoding of a per-user **change sequence**, stamped under a per-user clock-row lock, so it follows commit order. It deliberately does not use `(updated_at, id)`:

- `updated_at` is server time on write
- the LWW term (`sync_updated_at`) is client time, so a late offline upload can carry an older value than rows already paged past
- neither is in commit order

An unchanged pull returns the request's cursor verbatim. That is the client's "nothing changed" signal (`SyncEngine`), not an `ETag`.

`study_session_events` stores stopped sessions' logs **without** `uptime_millis` or `boot_id`. ADR 0017 treats those as device-local anchors, so the API drops them on upload and never returns them. This supersedes the `study_sessions` migration's comment that the log "never leaves the device (ADR 0003)". Applied migrations are append-only, so that comment is corrected in `20260927130000_session_sync.sql` rather than edited in place.

**Task and material sync (ADR 0018).** `GET`/`POST /v1/sync/records` behave the same way through
`sync_pull_records` and `sync_push_records`, and resolve conflicts by the same rule. The
differences:

- `sync_records` stores each task or material as the client's versioned JSON `payload`. The
  server does not interpret it; it resolves conflicts on the envelope alone, and the winning
  record replaces the stored one.
- Records are keyed per owner by `(user_id, entity_type, entity_id)`.
- The stream has its own change clock, and its cursors use a different encoding. A session cursor
  sent to the record stream, or the reverse, is refused with `invalid_cursor`.
- The Edge Function checks each batch before storing it:
  - at most 200 changes;
  - each payload at most 64 KiB;
  - a material is refused unless it has a `remoteKey`.
- Account deletion cascades to `sync_records` and `sync_record_clocks`.
- Tasks now replicate as records, so `study_sessions.task_id` keeps the device's task id as sent.
  It is no longer a foreign key to `study_tasks`.

Deploy it with the **Deploy Edge Function** workflow by choosing `api`, or locally with:

```sh
export SUPABASE_ACCESS_TOKEN=...
export GOOGLE_SERVER_CLIENT_ID=...
export PASSKEY_RP_ID=auth.example.com # Replace with your owned relying-party domain.
export PASSKEY_ANDROID_ORIGIN=android:apk-key-hash:...
export API_MINIMUM_CLIENT_VERSION=1.0.0
export API_BACKUP_RETENTION_DAYS=30 # replace with the verified provider backup lifetime
export STORAGE_S3_ENDPOINT=...
export STORAGE_S3_BUCKET=...
export STORAGE_S3_ACCESS_KEY_ID=...
export STORAGE_S3_SECRET_ACCESS_KEY=...
./infra/scripts/deploy-storage-function.sh <project-ref> api
```

### Presigned storage service

`supabase/functions/storage` handles ordinary object transfers; `api` also receives S3 credentials
for account deletion. Its primary deployment path is the manually-run **Deploy Edge Function** GitHub
Actions workflow with `storage` selected: select it in the **Actions** tab, choose **Run workflow**,
and tap **Run workflow**. It uses the `SUPABASE_PROJECT_REF` repository variable unless the optional
project ref input overrides it. Like the database deploy, it works from a mobile browser without a local
machine, Docker, or Supabase CLI.

The required deployment order is:

1. Run **Deploy backend** to apply the database migrations.
2. Run **Deploy Edge Function** with `storage` selected.
3. Set `STORAGE_REAPER_URL` and `STORAGE_REAPER_TOKEN` as described above.
4. The hourly `reap-storage-orphans.yml` workflow starts succeeding.

Until step 3 is complete, the reaper workflow fails its `test -n` guard. This is expected and
harmless. `ORPHAN_REAPER_TOKEN` and `STORAGE_REAPER_TOKEN` are two copies of one self-generated
secret on opposite ends of the reaper's HTTP call; neither value can be looked up before it is
created. Similarly, `STORAGE_REAPER_URL` does not exist until the function is deployed.

An optional malware scanner can be enabled with `STORAGE_SCAN_HOOK_URL` and
`STORAGE_SCAN_HOOK_TOKEN`. The hook must return `{"clean":true,"sha256":"<expected digest>"}`;
rejected objects are deleted before their metadata becomes downloadable.

For a local deployment, export the same settings and run
`./infra/scripts/deploy-storage-function.sh <project-ref> storage`. The pinned Supabase CLI version in both
deploy workflows must remain compatible with the feature set in `supabase/config.toml`. Older CLI
versions fail before deployment with `has invalid keys` errors for settings including `local_smtp`,
`storage.s3_protocol`, `storage.analytics`, `storage.vector`, `db.health_timeout`,
`auth.oauth_server`, `auth.external.apple.email_optional`, and `experimental.pgdelta`.

## Operations

Backend dashboards, alerts, cost monitoring, abuse response and the backup/restore drill are tracked
in [`docs/backend-observability.md`](../docs/backend-observability.md). The database migrations expose
the dashboard views and alert/lifecycle policy rows; the Edge Function writes only sanitized request
telemetry and never logs presigned URLs, object keys or user identifiers.
