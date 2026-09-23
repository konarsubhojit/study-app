# 13. Data lifecycle: a user-owned zip archive, an idempotent merge, and a server-first deletion

- Status: accepted
- Date: 2026-10-09

## Context

The app holds a student's study history: sessions and their event logs, tasks, subjects, lecture
files. Until now that data could only leave the device by syncing to our server, and it could only
be deleted by uninstalling — which leaves the server copy untouched. Neither Play's Data Safety
requirements nor the user's own expectation of ownership is met by that.

Four problems have to be solved at once, and each has an obvious wrong answer:

- **Getting the data out.** The obvious answer — write a file to shared storage — needs storage
  permissions, picks a location the user did not choose, and is a scoped-storage migration waiting
  to happen.
- **Putting it back.** An import that inserts what it reads duplicates everything on the second
  run, and clobbers newer local edits on the first. A restore that is not safe to repeat is a
  restore nobody dares repeat.
- **Deleting the account.** The ordering is load-bearing. Wiping the device first destroys the
  credentials the deletion request is authenticated with; if the network then fails, the account
  is permanently undeletable. Stopping halfway through the local wipe leaves orphaned study data.
- **Backup.** Android's automatic backup would happily copy the Keystore-sealed token store (which
  is unreadable elsewhere), the caches (which are worthless) and the active-timer anchor — which
  would restore onto a new device as a session that has "been running" since the backup was taken.

## Decision

**The archive is a zip written through the Storage Access Framework.** `ACTION_CREATE_DOCUMENT`
means the user picks the destination, the app needs no storage permission, and the file lives
somewhere the user already trusts. It contains one JSON document plus the original cached material
files as separate entries, so parsing the catalogue stays cheap however many gigabytes of recorded
lectures sit next to it.

**The JSON is a pinned wire format, versioned, not the domain models.** `DataArchive` is a separate
set of `@Serializable` classes of primitives and strings, ISO-8601 instants and millisecond
durations. An export a student keeps for two years must outlive our refactors, and a format that is
a projection of today's `data class` cannot promise that. `schemaVersion` is checked on read and a
newer archive is refused rather than silently half-read.

**The merge is idempotent by construction.** Records match by id; conflicts resolve last-write-wins
on `updatedAt`; a tie keeps the local row, which makes a second import of the same archive a no-op
rather than a re-write. Materials fall back to matching on content hash, so the same file under a
different id is not restored twice. Subjects, which have no `updatedAt`, are insert-only.

**Imported sessions are always stopped.** The archive's device id, boot id and wall clock are gone;
an imported `RUNNING` session would violate the single-active-session invariant and show a timer
counting since a date in the past. They are restored as `STOPPED` with `manualOverride = true`.

**Archives are read as hostile input.** The importer reuses `ArchiveSafety` — the zip-slip and
zip-bomb defence already used for material archives (ADR 0005). Entries that escape the root or lie
about their size are skipped and reported; the rest of the archive still restores. An entry name
never becomes a path: restored bytes are stored under the material's id.

**Deletion is server-first, local-complete, credentials-last.** `DELETE /v1/account` runs first and
a failure aborts with nothing touched locally, so the user can retry. Once the server accepts, every
local eraser runs to completion even if one fails, and the failures are reported by name with an
instruction to clear app data. Tokens are erased last because they are what an intermediate retry
needs. A `404` counts as success, making a retried deletion idempotent. The retention window comes
from the server's receipt, defaulting to 30 days, and the UI shows the number it was given rather
than a hard-coded promise.

**Backup stays off, and the rules say so explicitly.** `allowBackup="false"` is kept, but both rule
files now enumerate the exclusions — token store, active-timer anchor, settings, databases, cached
materials, caches — and a Robolectric test asserts them. The rules are a privacy promise, so they
are executable rather than reviewable.

**The rules live in `:core:domain`.** The archive model, codec, merge policy, exporter, importer and
deletion coordinator are pure JVM behind ports (`LocalDataReader`, `LocalDataWriter`,
`MaterialFileStore`, `ArchiveSinkFactory`, `ArchiveSourceFactory`, `RemoteAccountEraser`,
`DataEraser`), implemented by `:core:database`, `:core:datastore`, `:core:network`, `:core:storage`
and `:feature:settings`. The *order of erasure* is assembled in `:app`, where it is one reviewable
list rather than a rule scattered across whichever module owns each store.

## Consequences

- The round trip — export, wipe, import — is testable on the JVM in milliseconds against in-memory
  fakes, and end-to-end against a real Room database in `:core:database`.
- A second import, a partially corrupted archive and a hostile entry name are all defined
  behaviours with tests, not accidents of the code.
- The user's copy is a plain zip with readable JSON: portable, inspectable, and not dependent on us
  staying in business.
- A restored device cannot resurrect a running timer, and cannot restore credentials it could not
  decrypt anyway.
- **Cost:** the wire format is a second set of types that must be updated alongside the domain
  models, with a version bump. That duplication is the price of the two-year promise.
- **Cost:** an export is a point-in-time copy, not a backup service. A user who never exports and
  loses their phone still relies on sync.
- **Follow-up:** imported rows are not re-queued for upload, so a restore does not push history back
  to the server. Doing so safely needs a sync-engine decision about rate limits and tombstone
  resurrection (ADR 0012) and is deliberately out of scope here.
- **Follow-up:** the server-side deletion job, its retention window and the purge of pre-deletion
  backups are owned by the backend (ADR 0007 — backend platform); this ADR fixes only what the app
  promises and displays.
