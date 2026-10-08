# 20. Keep session sync off the generic record stream

- Status: accepted
- Date: 2026-10-08

## Context

ADR 0018 introduced `/v1/sync/records` for tasks and materials, next to the session stream from
ADR 0012 and ADR 0017. The client now drains one shared Room queue, then pulls sessions and records
as two streams; `SyncEngine` filters queued `SESSION` entries to `push`, non-session entries to
`pushDocuments`, and reads `store.cursor()` before `store.documentCursor()`.
[`SyncEngine.kt:78-142`](../../core/domain/src/main/kotlin/dev/studyflow/core/domain/sync/SyncEngine.kt#L78-L142)
The ports make those streams explicit: sessions use `SyncSessionRecord`, records use `SyncDocument`,
and the record cursor is documented as independent of the session cursor.
[`SyncPorts.kt:21-44`](../../core/domain/src/main/kotlin/dev/studyflow/core/domain/sync/SyncPorts.kt#L21-L44)

The two streams share real machinery already. The queue row is `(entity_type, entity_id,
operation, updated_at, device_id)` with a unique index on `(entity_type, entity_id)`, and it is
acknowledged by `sequence`.
[`SyncEntities.kt:26-48`](../../core/database/src/main/kotlin/dev/studyflow/core/database/entity/SyncEntities.kt#L26-L48)
Inbound pages advance a cursor in the same transaction that stores their changes, for both
`applyPage` and `applyDocumentPage`.
[`SyncDao.kt:113-129`](../../core/database/src/main/kotlin/dev/studyflow/core/database/dao/SyncDao.kt#L113-L129),
[`SyncDao.kt:220-243`](../../core/database/src/main/kotlin/dev/studyflow/core/database/dao/SyncDao.kt#L220-L243)
The conflict comparator itself is already shared as `LastWriteWins`: later `updatedAt`, then greater
`deviceId`, then tombstone over live row.
[`LastWriteWins.kt:5-47`](../../core/domain/src/main/kotlin/dev/studyflow/core/domain/sync/LastWriteWins.kt#L5-L47)

The data models are not the same. A session sync record is a projection plus its append-only event
log, because the log is the source of truth and the projection is only a fold of it.
[`SyncModels.kt:50-66`](../../core/domain/src/main/kotlin/dev/studyflow/core/domain/sync/SyncModels.kt#L50-L66)
Session merge applies LWW only to metadata and always unions events by id, keeping existing events
and ordering by `(sequence, id)`.
[`SessionSyncMerge.kt:36-48`](../../core/domain/src/main/kotlin/dev/studyflow/core/domain/sync/SessionSyncMerge.kt#L36-L48),
[`SessionSyncMerge.kt:74-119`](../../core/domain/src/main/kotlin/dev/studyflow/core/domain/sync/SessionSyncMerge.kt#L74-L119)
By contrast, a record is a whole task or material aggregate with LWW metadata; `DocumentSyncMerge`
keeps or replaces the whole document and only preserves device-local reminder and material state.
[`SyncModels.kt:68-84`](../../core/domain/src/main/kotlin/dev/studyflow/core/domain/sync/SyncModels.kt#L68-L84),
[`DocumentSyncMerge.kt:24-36`](../../core/domain/src/main/kotlin/dev/studyflow/core/domain/sync/DocumentSyncMerge.kt#L24-L36),
[`DocumentSyncMerge.kt:50-82`](../../core/domain/src/main/kotlin/dev/studyflow/core/domain/sync/DocumentSyncMerge.kt#L50-L82)

The wire formats reflect that split. `/v1/sync/sessions` sends session fields, `deleted`, folded
millis and an `events` array; event `uptimeMillis` and `bootId` are optional because the server
accepts then drops those device-local anchors.
[`Dtos.kt:135-184`](../../core/network/src/main/kotlin/dev/studyflow/core/network/model/Dtos.kt#L135-L184)
`/v1/sync/records` sends `entityType`, `schemaVersion`, and opaque `payload` instead.
[`Dtos.kt:218-251`](../../core/network/src/main/kotlin/dev/studyflow/core/network/model/Dtos.kt#L218-L251)
The Edge Function has separate routes, cursor prefixes, validators and RPCs for the two streams.
[`index.ts:415-423`](../../infra/supabase/functions/api/index.ts#L415-L423),
[`index.ts:487-510`](../../infra/supabase/functions/api/index.ts#L487-L510),
[`index.ts:633-659`](../../infra/supabase/functions/api/index.ts#L633-L659),
[`index.ts:1168-1200`](../../infra/supabase/functions/api/index.ts#L1168-L1200)

The backend schemas also encode different meanings. `study_sessions` has a per-user change clock,
`sync_updated_at`, and `study_session_events`; the session pull returns events, and push inserts
events with `ON CONFLICT ... DO NOTHING`, touching the parent row when only new events arrived.
[`20260927130100_session_sync.sql:28-47`](../../infra/supabase/migrations/20260927130100_session_sync.sql#L28-L47),
[`20260927130100_session_sync.sql:110-126`](../../infra/supabase/migrations/20260927130100_session_sync.sql#L110-L126),
[`20260927130100_session_sync.sql:158-198`](../../infra/supabase/migrations/20260927130100_session_sync.sql#L158-L198),
[`20260927130100_session_sync.sql:310-321`](../../infra/supabase/migrations/20260927130100_session_sync.sql#L310-L321)
`sync_records` stores one keyed row per task or material with `schema_version` and `payload`, and
`sync_push_records` replaces the whole payload when the incoming envelope wins.
[`20260928150000_record_sync.sql:32-44`](../../infra/supabase/migrations/20260928150000_record_sync.sql#L32-L44),
[`20260928150000_record_sync.sql:94-110`](../../infra/supabase/migrations/20260928150000_record_sync.sql#L94-L110),
[`20260928150000_record_sync.sql:123-192`](../../infra/supabase/migrations/20260928150000_record_sync.sql#L123-L192)

Migration would be costly and risky. The server would need to backfill existing session rows and
events into `sync_records`, after the session migration already backfilled `sync_updated_at` and
`change_seq` in place.
[`20260927130100_session_sync.sql:49-68`](../../infra/supabase/migrations/20260927130100_session_sync.sql#L49-L68)
The app would need a Room migration to change cursors and queue semantics; the current migration
created one queue and state table, and the record migration later queued only tasks and uploaded
materials, explicitly not sessions.
[`DatabaseMigrations.kt:244-274`](../../core/database/src/main/kotlin/dev/studyflow/core/database/DatabaseMigrations.kt#L244-L274),
[`DatabaseMigrations.kt:277-298`](../../core/database/src/main/kotlin/dev/studyflow/core/database/DatabaseMigrations.kt#L277-L298),
[`DatabaseMigrations.kt:377-384`](../../core/database/src/main/kotlin/dev/studyflow/core/database/DatabaseMigrations.kt#L377-L384)
Two app versions would also be in the field: current clients call `/v1/sync/sessions`, while a
migrated client would call `/v1/sync/records`.
[`StudyFlowApi.kt:71-95`](../../core/network/src/main/kotlin/dev/studyflow/core/network/StudyFlowApi.kt#L71-L95)

## Decision

Do not move sessions onto `/v1/sync/records`. Sessions remain on `/v1/sync/sessions` because their
append-only event-union semantics do not fit a whole-record LWW stream without either losing events
or inventing a session-specific sub-protocol inside the generic payload.
[`SessionSyncMerge.kt:103-119`](../../core/domain/src/main/kotlin/dev/studyflow/core/domain/sync/SessionSyncMerge.kt#L103-L119),
[`20260928150000_record_sync.sql:120-121`](../../infra/supabase/migrations/20260928150000_record_sync.sql#L120-L121)

Extract shared pieces instead:

- Keep `LastWriteWins` as the shared client conflict primitive, and keep server SQL comparisons
  pinned to the same rule for both RPC families.
- Keep queue draining and cursor-atomic page application behind `SyncEngine`, `SyncStore`, and
  `SyncDao`; factor common Room helpers only where they preserve separate session and record
  merge functions.
- Factor Edge Function cursor encoding, limit parsing, batch acknowledgement validation, and common
  tombstone/envelope validation into a sync helper module under `infra/supabase/functions/api/`,
  while leaving `sync_push_study_sessions` and `sync_push_records` as separate RPCs.
- Keep `TwoDeviceConvergenceTest` as the safety net for shared behavior: it already runs two real
  Room databases through session convergence, record cursor persistence, task conflicts, deletes,
  and material replication.
  [`TwoDeviceConvergenceTest.kt:53-59`](../../core/database/src/test/kotlin/dev/studyflow/core/database/sync/TwoDeviceConvergenceTest.kt#L53-L59),
  [`TwoDeviceConvergenceTest.kt:79-176`](../../core/database/src/test/kotlin/dev/studyflow/core/database/sync/TwoDeviceConvergenceTest.kt#L79-L176),
  [`TwoDeviceConvergenceTest.kt:260-319`](../../core/database/src/test/kotlin/dev/studyflow/core/database/sync/TwoDeviceConvergenceTest.kt#L260-L319)

If this is revisited later, require a version-flagged rollout that dual-writes sessions to both
streams, lets old clients drain `/v1/sync/sessions`, verifies convergence in `TwoDeviceConvergenceTest`,
and only then removes the old stream. That is not justified by the current evidence.

## Consequences

- Session sync keeps the semantics users need: active sessions stay local, stopped sessions replicate
  their logs, stale edits lose to newer tombstones, and event halves union instead of overwriting.
- The generic record stream stays generic: one opaque payload, one envelope, one whole-record LWW
  winner for tasks and materials.
- Shared implementation effort goes into cursor, queue, tombstone and validation utilities rather
  than a risky data migration.
- Maintaining two public sync routes remains a cost, but it is cheaper than supporting a hidden
  session event protocol inside `payload` plus a compatibility migration for deployed clients.
