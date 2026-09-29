# 18. Replicate tasks and materials through a generic record stream

- Status: accepted
- Date: 2026-09-28

## Context

[ADR 0012](0012-offline-first-sync.md) built offline-first sync for completed study sessions and
listed tasks, subjects and materials as a known gap. Epic 6's exit criterion is stricter: sign in
on a second device and *all* completed sessions, tasks and materials appear, with conflicting
edits resolved deterministically.

ADR 0012 gave two reasons for leaving tasks out. There was no write protocol for them, and
`TaskDto` loses data compared with the local model: it has no recurrence grammar, checklist or
reminders. The relational `study_tasks` and `materials` tables on the server have the same
problem. They model a subset of each aggregate, so mapping every field in both directions would
mean one migration per client model change, and would quietly drop whatever the server model
lacks.

The app already has a complete, versioned, tested format for these aggregates: the export archive
(`ArchivedTask`, `ArchivedMaterial`, [ADR 0013](0013-data-lifecycle.md)).

## Decision

**One generic record stream, `/v1/sync/records`, carries tasks and materials** (entity types
`task` and `material`). It sits beside the session stream and works the same way: an outbound
queue, a delta pull with an opaque commit-ordered cursor, tombstones, and per-record
accept/reject.

- **The payload is the export format, and it is opaque to the server.** `SyncDocumentCodec`
  encodes a task (checklist, tags and reminder rules included) or a material's metadata as its
  archive JSON, plus a `schemaVersion`. The server stores it as `jsonb` and resolves conflicts
  only on the envelope (`updatedAt`, `deviceId`, `deleted`). The client can therefore change its
  model without a server migration, and a device that receives a newer `schemaVersion` than it
  understands drops and logs that record rather than failing the whole page.
- **Conflicts use one rule everywhere.** `LastWriteWins` is shared by sessions and records: a
  later `updatedAt` wins; an exact tie goes to the greater `deviceId` in code-point order; at the
  same instant from the same device, a tombstone beats a live row. `sync_push_records` implements
  the same rule with the `"C"` collation. `record_sync.sql` and `DocumentSyncMergeTest` pin the
  two sides to each other. The winner replaces the whole record; there is no field-level merge.
- **The server owns no identity.** Records are keyed by `(user_id, entity_type, entity_id)`, so
  two accounts can never collide on an id, and `DELETE /v1/account` removes them (and the change
  clock) by cascade from the Auth user.
- **Device-local state never leaves the device.** Some state is device-scoped:
  - for reminders: registered alarms (`schedulingId`), snoozes and `lastFiredAt`;
  - for materials: the downloaded file (`localPath`), offline pins, preview position, playback
    speed and upload progress.

  The codec strips all of these, and `DocumentSyncMerge` keeps the receiving device's own copy.
  An inbound reminder is stamped `lastFiredAt = receivedAt`, so a trigger that is already past
  when a second device first learns of a task is not replayed as a burst of stale
  notifications. The next occurrence is scheduled normally: after a pull that applied changes,
  `SyncWorker` asks `ReminderIntegrityCoordinator` to reconcile alarms.
- **A material replicates only once its bytes are stored.** The server rejects an un-uploaded
  material, because another device could never fetch its file. Download progress does not bump
  `updatedAt` and is not re-sent. Object keys are opaque, and download resolves them inside the
  caller's own storage namespace. Once a material reaches another device, that device can fetch
  it on demand.
- **Every stored row records its last writer.** `study_tasks` and `materials` gain `device_id`
  (Room version 13), so the tie-break compares the same values on every device. Every local
  mutation queues the aggregate in the same transaction. For materials, only changes to
  replicated fields are queued.
- **Links are kept as they are; unresolvable references are dropped.** A task's `materialId` and
  `sessionId` are kept as sent, even if the target has not arrived yet. The local foreign keys
  that would have nulled them are removed, as is the server's `study_sessions.task_id` foreign
  key. Subjects and folders still do not sync, so an inbound reference to one this device does
  not have is dropped to `null`, following the precedent set for sessions.

### Lifecycle rules

- **Local-only users are unaffected.** Mutations still queue, but `SyncWorker` does nothing
  without a signed-in account. No functionality depends on the network.
- **Sign-in requests a sync**, so a second device fills without waiting for the periodic run.
- **Sign-out resets sync state for the next account.** `SyncStore.resetForAccountChange()`
  clears both cursors and re-queues every syncable row: stopped sessions, tasks, and uploaded
  materials. The next account then reads its history from the start and receives this device's
  data. Pushing to an account that already has the data is harmless, because a replay is a
  no-op.
- **Upgrade.** Migration 12→13 queues existing tasks and uploaded materials, which have never
  been replicated. Sessions keep version 12's rule of not re-queuing existing history wholesale.

## Consequences

- A second device now receives completed sessions, tasks (with recurrence, checklist, tags and
  reminder rules) and uploaded materials. `TwoDeviceConvergenceTest` runs two real Room
  databases through the real codec to prove each case, including conflicting edits and deletes.
- Adding an entity type is additive: a codec branch, a merge branch and a value in the server's
  `entity_type` check.
- The relational `study_tasks` and `materials` tables on the server are no longer the source of
  truth for sync. They still back `GET /v1/tasks`, which does not see replicated tasks. Retiring
  those tables or projecting records into them is follow-up work.
- The whole-record LWW rule means that concurrent edits to *different* fields of the same task
  keep only the later edit. As with sessions, determinism wins over merging.
- Subjects, folders and material tags still do not replicate, so a material's folder and a
  task's subject appear on a second device only if that device already has them.
