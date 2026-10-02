# 17. Serve the API contract through one Edge Function and replicate meaningful session events

- Status: accepted
- Date: 2026-09-27

## Context

[ADR 0007](0007-backend-platform.md) chose Supabase as the backend platform, and
[ADR 0014](0014-api-contract-and-network-client.md) made `docs/api/openapi.yaml` the client/server
contract. Neither says what serves that contract. Exposing the existing tables directly through
PostgREST looks attractive because it avoids an application layer, but the database and wire
models are not the same model:

| Contract field | Stored field | Why a direct PostgREST response is wrong |
| --- | --- | --- |
| `SubjectDto.colorHex: String?` | `subjects.color_argb integer not null` | There is no `colorHex` key. The client ignores unknown fields and defaults the missing nullable property to `null`, so parsing succeeds and every subject renders without its colour. |
| `TaskDto.completed: Boolean = false` | `study_tasks.completed_at timestamptz` | There is no `completed` key. Parsing succeeds with `false`, so every completed task appears incomplete. |
| `TaskDto.dueAt` (`format: date-time`) plus `TaskDto.dueAtTimeZone` | `study_tasks.due_at timestamp` plus `time_zone text` | The migration deliberately keeps a naive wall-clock value and its zone separate so a due time does not drift across DST. Returning one database column cannot implement the contracted value without interpreting both, and the contract carries the zone alongside the resolved instant so the floating wall-clock meaning is recoverable rather than frozen at the offset it was read under. |
| required `TaskDto.subjectId: String` | nullable `study_tasks.subject_id` with `on delete set null` | A task whose subject was deleted cannot satisfy the required wire field. |

These claims come from
[`Dtos.kt`](../../core/network/src/main/kotlin/dev/studyflow/core/network/model/Dtos.kt),
[`openapi.yaml`](../api/openapi.yaml),
[`20260916090200_subjects.sql`](../../infra/supabase/migrations/20260916090200_subjects.sql), and
[`20260916090400_study_tasks_and_reminders.sql`](../../infra/supabase/migrations/20260916090400_study_tasks_and_reminders.sql).
In particular, `Dtos.kt` documents that unknown response fields are dropped. The first two cases
are therefore error-free wrong results, not authentication failures or obvious decoding errors.

Direct gateway use also has secondary mismatches. Supabase's gateway expects the project API key
on its `/rest/v1/*` and `/auth/v1/*` surfaces, while
[`StudyFlowHttpClient.kt`](../../core/network/src/main/kotlin/dev/studyflow/core/network/http/StudyFlowHttpClient.kt)
adds JSON content type and `X-Client-Version`, and its auth plugin adds only
`Authorization: Bearer`. Putting the public anon key in `BuildConfig` would still ship a new
credential and widen the client's knowledge of the provider, against the capability-oriented
boundary in [ADR 0005](0005-object-storage.md).

The missing `X-Minimum-Client-Version` response header does **not** force this choice:
[`MinimumClientPolicy`](../../core/network/src/main/kotlin/dev/studyflow/core/network/version/ClientVersion.kt)
treats a missing or unparseable header as “no upgrade required.” Serving responses without it would
instead give up the force-upgrade lever promised by ADR 0014. PostgREST error bodies also differ
from `ApiErrorDto` (notably nullable diagnostic fields and `hint`);
[`errorBodyOrNull()`](../../core/network/src/main/kotlin/dev/studyflow/core/network/KtorStudyFlowApi.kt)
swallows decoding failures. That loses server diagnostics but does not itself break a request.

There is a second unresolved contradiction. [ADR 0012](0012-offline-first-sync.md), dated
2026-10-02, says:

> A session's event log merges by union on id, and is never overwritten. The projection follows the
> winning session row, but two devices holding different halves of one session's log must end up
> with both halves.

[`TwoDeviceConvergenceTest`](../../core/database/src/test/kotlin/dev/studyflow/core/database/sync/TwoDeviceConvergenceTest.kt)
encodes that rule and compares the devices' converged event IDs. However,
[`20260916090300_study_sessions.sql`](../../infra/supabase/migrations/20260916090300_study_sessions.sql),
dated 2026-09-16, says that the event log “never leaves the device (ADR 0003),” and no migration
defines a session-event table. Its [ADR 0003](0003-timer-event-sourcing.md) citation explains local
timer event sourcing and predates the replication decision in ADR 0012; it does not settle the
later cross-device question.

Finally, the contract has no sign-in operation. `openapi.yaml` and
[`ApiEndpoint`](../../core/network/src/main/kotlin/dev/studyflow/core/network/ApiConfig.kt) contain
only refresh. [`authentication.md`](../authentication.md) says the app sends a WebAuthn assertion
or Google ID token to the StudyFlow API in exchange for a token pair, and
[`CredentialManagerSignInClient`](../../feature/auth/src/main/kotlin/dev/studyflow/feature/auth/CredentialManagerSignInClient.kt)
produces those proofs, but no API method consumes them. `/v1/auth/refresh` can only refresh a pair
that nothing currently mints.

## Decision

### Serve the StudyFlow contract through an Edge Function

A single Supabase Edge Function named `api` will implement the paths and representations in
`docs/api/openapi.yaml`. Stock PostgREST will remain an internal persistence interface, not the
public StudyFlow API.

| Option | Result |
| --- | --- |
| Expose tables through stock PostgREST | Rejected. It leaks storage names and shapes and silently corrupts the meaning of subject colours and task completion, in addition to the due-time and nullability mismatches above. |
| Add database views/functions until PostgREST resembles the contract | Rejected. It moves HTTP representation logic into the database, still leaves version headers and error translation elsewhere, and couples the public API to the storage schema. |
| Translate in a Supabase Edge Function | Chosen. One boundary maps database rows to DTOs, emits contract headers and errors, and keeps the provider behind the API chosen by ADR 0014. |

This extends [ADR 0007](0007-backend-platform.md): “Supabase” now means Edge Functions at the public
API boundary, with PostgREST behind them. It also supplies the server-side mechanism left open by
[ADR 0014](0014-api-contract-and-network-client.md).

The function's deployed base URL is
`https://<project-ref>.supabase.co/functions/v1/api`. `ApiConfig.urlOf()` already computes
`normalizedBaseUrl + endpoint.path`, so an endpoint such as `/v1/tasks` becomes
`.../functions/v1/api/v1/tasks`. This requires no change to `ApiEndpoint`, `openapi.yaml`, or
`OpenApiContractTest`.

The consolidated function must route on the complete path and method. The existing
[`storage` function](../../infra/supabase/functions/storage/index.ts) selects an operation from only
the last path segment. That cannot distinguish `/v1/sessions` from `/v1/sync/sessions`, so it is not
a pattern the `api` router may copy. A later implementation should consider folding the storage
operations from [ADR 0010](0010-storage-provider.md) into the same full-path router; this ADR does
not require that consolidation.

Like `storage`, `api` must set `verify_jwt = false` in
[`config.toml`](../../infra/supabase/config.toml) and verify the bearer token inside the function.
This lets the function own the contract's authentication and error shape rather than allowing the
gateway to answer first in a different format. `false` means the platform does not perform the
check; it does not make StudyFlow's authenticated operations public.

Production builds require a Supabase project ref or explicit API and storage base URLs; there is
no default deployment. Supabase offers custom domains on paid plans, but adopting one is not
recommended by this decision. The contract lists the Supabase function URL and must retain
`http://10.0.2.2:8080`, which
[`OpenApiContractTest`](../../core/network/src/test/kotlin/dev/studyflow/core/network/OpenApiContractTest.kt)
requires.

### Replicate stopped-session event logs without device-local anchors

Stopped sessions replicate their event logs. At the server boundary, each event keeps:

- `id`
- `sessionId`
- `type`
- `sequence`
- `wallClock`

The boundary drops `uptimeMillis` and `bootId`; the server neither stores nor returns them. These
are local monotonic-clock anchors. ADR 0012 already excludes running and paused sessions because
their anchors “mean nothing on another handset.” A boot identifier from a different device is
equally uninterpretable after a session stops and should not become replicated data.

The retained fields are sufficient to union logs by event `id` and to replay or display them in
deterministic `(sequence, id)` order. This decision explicitly narrows
[ADR 0012](0012-offline-first-sync.md): devices converge on the meaningful replicated event fields,
not byte-for-byte copies of local event rows. Its union-on-id rule remains in force.
`TwoDeviceConvergenceTest` will probably need to compare that replicated projection when the
boundary is implemented; this ADR does not change the test.

The 2026-09-16 migration comment saying the log never leaves the device is stale and must be
corrected when a new migration adds the event table. Existing migrations are append-only, so this
documentation-only change does not edit it.

### Deprecate the opportunistic session upload

`POST /v1/sessions` is marked for future deprecation. ADR 0012 replaced opportunistic upload with
the transactional queue at `/v1/sync/sessions`; implementing both would create two write paths with
different conflict semantics against the same session data.

The endpoint has not been implemented, so this decision breaks nothing now. Its eventual removal
must follow `openapi.yaml`'s policy: continue serving it for the deprecation window with a
`Deprecation` header, then return `410 Gone` with `endpoint_removed`. This explicitly supersedes
ADR 0014 only in its expectation that every currently listed operation will be implemented.

### Record, but do not solve, authentication and deletion prerequisites

The missing sign-in operation blocks all authenticated endpoints and must be specified in its own
change before this API can be implemented. The refresh operation needs a thin translation shim,
not a redirect to GoTrue: StudyFlow uses `refreshToken`, `accessToken`, and `expiresInSeconds`,
where GoTrue uses `refresh_token`, `access_token`, and `expires_in`.

`DELETE /v1/account` also belongs in the Edge Function. Deleting the `auth.users` row requires the
service-role key; its owned database rows can then cascade, while objects in the S3-compatible
store chosen by [ADR 0010](0010-storage-provider.md) must be removed separately. A client cannot
orchestrate those privileged operations, and the function must return the idempotent retention
receipt required by [ADR 0013](0013-data-lifecycle.md).

These are constraints and prerequisites, not additional endpoint designs in this ADR.

## Consequences

- The public API has one translation and policy boundary. Database naming, nullability, and
  provider-specific errors do not leak into the Android contract.
- The deployed function URL works with the existing client's base-URL mechanism and leaves the
  checked-in paths and local mock-server test unchanged.
- Full-path routing prevents collisions as the API grows, at the cost of building and testing a
  real router rather than dispatching on a final path segment.
- The Edge Function must implement DTO mapping, authentication, version headers, structured
  errors, authorization, and pagination correctly. PostgREST would have supplied less code, so
  this is a deliberate maintenance and latency cost paid to preserve meaning.
- Event-log union can recover different halves of one stopped session across devices without
  persisting meaningless monotonic-clock anchors.
- Convergence is now defined over meaningful replicated fields, not byte-identical local rows.
  The wire DTO, OpenAPI schema, database schema, mappers, and `TwoDeviceConvergenceTest` will need
  coordinated changes when this decision is implemented.
- A new append-only migration must add event storage and correct the stale migration comment; this
  ADR intentionally leaves the existing migration untouched.
- The old session upload remains in the contract until its deprecation lifecycle is implemented.
  That temporary inconsistency is preferable to silently removing a documented operation.
- No authenticated endpoint is deployable end to end until a separate decision specifies the
  sign-in request and token-minting response.
