# Data lifecycle: export, import, deletion and backup

The student owns their data. They can take a full copy of it off the device, put it back on a new
one, and have it deleted — everywhere — without asking anybody. This document is the contract:
what the archive contains, how a re-import behaves, what a deletion guarantees and how long the
server keeps anything afterwards.

→ [ADR 0013](adr/0013-data-lifecycle.md) ·
[`core/domain/.../lifecycle`](../core/domain/src/main/kotlin/dev/studyflow/core/domain/lifecycle) ·
[`DataPrivacyScreen`](../feature/settings/src/main/kotlin/dev/studyflow/feature/settings/DataPrivacyScreen.kt)

## Export

**Settings → Data & privacy → Export.** The app asks the system where the file should go
(`ACTION_CREATE_DOCUMENT`), so the archive lands wherever the user chose — Drive, an SD card, a
USB stick — and the app never needs storage permissions or a directory of its own.

The archive is a **zip** named `studyflow-export-<date>.zip` containing:

| Entry | Contents |
| --- | --- |
| `studyflow-archive.json` | Every record: subjects, sessions *with their full event log*, tasks (subtasks, reminders, recurrence), and material metadata. |
| `materials/<material id>-<display name>` | The original cached bytes of each material that is downloaded on this device. |

The JSON document is a pinned wire format
([`DataArchive`](../core/domain/src/main/kotlin/dev/studyflow/core/domain/lifecycle/DataArchive.kt)),
not the serialized domain models, so an archive kept for two years stays readable by an app that
has refactored its models since. It is versioned by `schemaVersion`; this build writes and reads
version **1** and refuses an archive written by a newer app rather than silently dropping fields it
does not understand.

Everything in it is a primitive or a string. Instants are ISO-8601 (`2026-09-23T02:49:21Z`) and
durations are whole milliseconds, so the file is legible in a text editor — being able to read your
own data is part of owning it.

```jsonc
{
  "schemaVersion": 1,
  "exportedAt": "2026-09-23T02:49:21Z",
  "deviceId": "…",          // the device the export was taken on
  "subjects": [ … ],
  "sessions": [ { "id": "…", "events": [ … ] } ],
  "tasks": [ … ],
  "materials": [ { "id": "…", "contentHash": "…", "archiveEntry": "materials/…" } ]
}
```

A material that is not downloaded on this device is exported as metadata with no `archiveEntry`
and is counted separately in the summary, so "3 items were not downloaded" is something the user
is told rather than something they discover later.

## Import

**Settings → Data & privacy → Import.** The user picks an archive; the merge is duplicate-safe by
construction, and these rules are the acceptance criteria that the tests assert:

- **Re-importing the same archive twice changes nothing.** Records match by id; identical records
  count as `unchanged` and are not written.
- **Newer local data wins.** Conflicts resolve last-write-wins on `updatedAt`. A tie keeps the
  *local* row, which is what makes a second import a no-op.
- **Materials match by id, then by content hash.** The same file restored under a different id
  does not become a second copy of a lecture recording.
- **Subjects are insert-only.** They carry no `updatedAt`, so an existing subject is never
  overwritten by an older copy of itself.
- **A running session never comes back running.** Imported sessions are forced to `STOPPED` with
  `manualOverride = true`: the archive's device, boot id and wall clock are all gone, and a timer
  that has "been running for nine days" is worse than an honest stopped one.
- **Hostile archives are refused entry by entry.** The archive is read through
  [`ArchiveSafety`](../core/domain/src/main/kotlin/dev/studyflow/core/domain/materials/ArchiveSafety.kt),
  the same zip-slip/zip-bomb defence the materials feature uses. An entry whose name escapes the
  root, or that lies about its size, is skipped and reported as `rejectedEntries`; the rest of the
  archive still restores. Entry names never become file paths — restored bytes are stored under the
  material's id.
- **A file that is not a StudyFlow archive fails as a validation error and writes nothing.**

**Known limitation.** Imported rows are merged into Room but are not re-queued for upload, so a
restore does not push the restored history back to the server. Syncing a restored device therefore
converges to the server's copy plus whatever the user changes afterwards. Re-queueing a full
restore is a sync-engine decision (rate limits, tombstone resurrection) and is deliberately left
to a follow-up.

## Account deletion

**Settings → Data & privacy → Delete account.** An explicit confirmation dialog spells out the
consequence and the retention window; nothing happens until it is accepted.

The order of operations is the design:

1. **The server first** — `DELETE /v1/account` removes the user's rows and their stored objects.
   A local wipe destroys the tokens the request is authenticated with, so wiping first and then
   failing on the network would leave an account nobody can ask to delete any more. **If the server
   refuses, nothing local is touched** and the user can retry.
2. **Then every local store, in order**: stored objects (uploads and thumbnails) → the Room
   database → cached material files → thumbnails → other caches → settings → the active-timer
   anchor → **credentials last**, because they are what an intermediate retry would need.
3. **Local erasure runs to completion even if a step fails.** The account is already gone by then;
   stopping halfway would leave orphaned study data behind. Each failure is reported by name and
   the user is told to clear the app's data in system settings to finish.

A device with no account signed in skips step 1 and erases local data identically, so "delete my
data" means the same thing signed in or not. A `404` from the server counts as success, so a
retried deletion is idempotent rather than permanently stuck.

### Retention window

The server's receipt carries the window it promises; the app defaults to **30 days** when the
server does not state one, and the UI shows whichever number came back rather than a hard-coded
sentence. Within that window, backups taken before the deletion are purged. Nothing is served from
them, and no restore path brings the account back.

## Backup and device transfer

`android:allowBackup="false"`. StudyFlow's answer to "how do I move to a new device" is the export
archive the user can see and control, not an opaque cloud copy of unknown age that would merge with
newer synced data.

[`data_extraction_rules.xml`](../app/src/main/res/xml/data_extraction_rules.xml) (Android 12+, cloud
backup and device-to-device transfer) and
[`full_backup_content.xml`](../app/src/main/res/xml/full_backup_content.xml) (older platforms) spell
the exclusions out in full anyway, so that anybody who later enables backup keeps the ones that
matter, and `BackupRulesTest` fails the build if they are weakened:

| Excluded | Why |
| --- | --- |
| `studyflow.auth.tokens` | Keystore-sealed access and refresh tokens. The key never leaves the device, so a restored copy is unreadable *and* a liability. The user signs in again. |
| `datastore/active_timer.pb` | The running-timer anchor. **A restored device must not resurrect a running timer** — the wall clock has moved and the foreground service is gone. |
| `datastore/user_settings.pb` | Preferences describe data the restore would not have. |
| Room databases, `files/materials` | Rows and cached files belong to the account or the export archive. |
| Caches and thumbnails | Reproducible, large, worthless in a backup. |

## What the Data Safety form declares

| Data type | Collected | Shared | Purpose | Optional | Deletable |
| --- | --- | --- | --- | --- | --- |
| Email address (account) | Yes | No | Account management, sign-in | Required for sync; the app is usable signed out | Yes, in-app |
| App activity — study sessions, tasks, subjects | Yes | No | App functionality (sync between devices) | Yes, only when signed in | Yes, in-app |
| Files and documents — study materials | Yes | No | App functionality (sync between devices) | Yes, only when signed in | Yes, in-app |
| Crash logs and diagnostics | Only when the user opts in | No | Diagnostics | Yes | Yes, in-app |

All data is encrypted in transit. Users can request deletion in the app, which is what the Play
Console "Data deletion" URL and the in-app route both point at. Nothing is sold, and nothing is
shared with third parties for advertising.

## Where the code lives

| Concern | Module |
| --- | --- |
| Archive format, JSON codec, merge policy, exporter, importer, deletion ordering | `:core:domain` (`lifecycle/`) |
| Reading and restoring rows | `:core:database` (`RoomDataLifecycleStore`) |
| Settings, timer anchor and token wiping | `:core:datastore` (`lifecycle/DataStoreErasers.kt`) |
| `DELETE /v1/account` | `:core:network`, with the HTTP↔domain seam in `:core:scheduling` (`ApiAccountEraser`) |
| Stored object deletion | `:core:storage` (`ObjectStoreEraser`) |
| Screen, SAF pickers, zip reading and writing | `:feature:settings` |
| Wiring, the order of erasure, backup rules | `:app` (`di/DataLifecycleModule.kt`, `res/xml/`) |
