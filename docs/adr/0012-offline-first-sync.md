# 12. Offline-first sync: a transactional change queue, a resumable delta, and last-writer-wins by `updatedAt` then `deviceId`

- Status: accepted
- Date: 2026-10-02

## Context

Until now the app wrote to Room and, separately, sometimes uploaded a session (`POST /v1/sessions`)
if the network happened to be there when the code ran. That is not offline-first, it is "online
with a grace period": a change made on a plane is lost to the other device unless the upload call
was reached, and nothing ever came *back* down, so two devices could never converge.

Making sessions replicate properly has four failure modes that are easy to hit and miserable to
debug after the fact:

- **A change that is written but never queued.** If the mutation commits and the "remember to sync
  this" row is written afterwards, a crash between the two loses the change forever — silently, and
  only for the user who was unlucky.
- **A delta pull that is not safe to interrupt.** Merging a page and remembering how far you got
  are two writes. Doing them apart means a killed process either re-reads the world every launch
  or skips a page permanently.
- **Conflicts resolved differently on each device.** Two devices that apply the same pair of
  changes in different orders and reach different answers is not a conflict *resolution* strategy,
  it is data corruption that converges to nothing.
- **A deletion that comes back.** The classic distributed-systems ghost: device A deletes a
  session, device B (which still has it) pushes its copy, and the session returns from the dead.
- **Data spent on nothing.** A sync engine that polls on every keystroke is a battery and data
  bill the user did not agree to.

## Decision

**The queue entry is written inside the mutation's own transaction.** `SessionDao` declares its own
`enqueueSyncEntry` and calls it from within the existing `@Transaction` of `appendAndProject` and
`applyCorrection` — not through `SyncDao`, and not in a callback afterwards. Either the session and
its queue row both exist, or neither does; there is no window in which a change is durable but
unqueued. `SyncQueueTransactionTest` asserts this by failing a transaction mid-flight and checking
that nothing survived.

**The queue records a reference, not a payload.** A row is `(entity_type, entity_id, operation,
updated_at, device_id)` under a unique index on `(entity_type, entity_id)`, inserted with
`REPLACE`. Three consequences, all deliberate:

- Editing one session ten times leaves *one* pending change, which is also the number the user sees
  in Settings — a count of edits would be an alarming and meaningless number.
- The drain reads the session's current state when it sends, so a coalesced entry can never ship a
  version the user has already moved past.
- `REPLACE` assigns a **new** autoincrement `sequence`, and the drain acknowledges by sequence
  rather than by id, so a mutation made while a batch is in flight is not thrown away by that
  batch's acknowledgement.

**Only stopped, non-active sessions are queued at all.** `SessionSyncMerge.isSyncable` is the single
place that decides, and it is consulted on both the outbound and the inbound path: a running or
paused session is a *local* device's timer state, full of uptime and boot-id anchors that mean
nothing on another handset, and replicating it would make "which device is the timer on?" a
question with two answers.

**Inbound delta: one page merged and its cursor advanced in one transaction.** `SyncDao.applyPage`
writes the merged sessions, their events and the new cursor together. A run killed halfway resumes
at the last page it *finished*; at worst it re-reads one page, and merges are idempotent, so that
costs a request rather than correctness. The cursor is opaque to the client — stored verbatim,
sent back verbatim — so the server can change how it paginates without an app release.

**Conflicts are last-writer-wins on `updatedAt`, tie-broken by `deviceId`, with tombstones
privileged.** The tie-break is not decoration: two devices whose clocks agree to the millisecond
would otherwise resolve the same pair in opposite directions, and *any* deterministic rule beats a
coin flip. The lexicographically greater `deviceId` wins, and on a full tie a tombstone beats a
live row. `SessionSyncMergeTest` states each rule directly, and `TwoDeviceConvergenceTest` proves
the property that actually matters: two real Room databases exchanging changes through a fake
server end up byte-identical regardless of order.

**A deletion is a tombstone row, never a missing row**, and the merge additionally drops any
still-pending local queue entry for an entity whose remote version won with a newer `updatedAt`.
Without that second rule a stale local edit would be re-pushed after losing, and the session would
resurrect on the next round trip.

**A session's event log merges by union on id, and is never overwritten.** Events are append-only
facts; two devices holding different halves of one session's log must end up with both halves.
Inserts use `IGNORE` so a re-delivered event is a no-op, and ordering is `(sequence, id)` so both
devices list the log identically.

**Nothing to do means no traffic.** A run triggered by a local mutation (`SyncTrigger.OUTBOUND`)
with an empty queue makes *zero* requests. A scheduled or manual run spends exactly one conditional
`GET` — the protocol's floor for "has anything changed?" — and writes nothing when the answer is
"no". `SyncEngineTest` asserts the request counts, because this is the kind of promise that decays
the moment nobody is checking.

**WorkManager owns *when*, the engine owns *what*.** The work is unique (`sync:sessions`), carries
a `NetworkType.CONNECTED` constraint so it simply does not run offline and starts itself when
connectivity returns, and backs off exponentially from the WorkManager minimum on retry. Automatic
triggers use `KEEP` (a run is already coming); "Sync now" uses `REPLACE`, because a user tapping a
button is asking for a run now rather than for their tap to be absorbed by a pending one. A
separate periodic request, enqueued once per cold start with `KEEP`, is what makes a device that
only *reads* converge: every other trigger is a local mutation or a button press, neither of which
happens on the tablet nobody studies on.

**Scope is completed study sessions only.** `docs/api/openapi.yaml` describes no write for tasks or
subjects, and `TaskDto` is lossy against the local model (it carries no recurrence grammar), so
queueing them would mean inventing a protocol and silently discarding user data on the wire. The
queue is typed (`SyncEntityType`) so a second entity is a new constant and a new branch, not a
second table and a second migration.

### The three-way case, written down

The scenario the issue asks about explicitly, and the answer this design gives:

1. Device **A** goes offline and edits a session's note at `T+10`.
2. Device **B**, online, edits the same session at `T+20`; the server takes it.
3. Someone deletes the session on the server at `T+30` (a tombstone with `updatedAt = T+30`).
4. **A** comes back online. Its queued edit is pushed and the server refuses it — it holds
   something strictly newer — which is reported as a *rejection*, not an error. A's queue entry is
   dropped all the same, because the delta that follows is what tells A the truth.
5. A's delta pull delivers the `T+30` tombstone. LWW picks it (`T+30 > T+10`), A's copy becomes a
   tombstone, and its pending entry for that session is removed as superseded.

**Outcome: the session is deleted on every device, and A's offline note is lost.** That loss is the
honest consequence of last-writer-wins, not an oversight: the alternative — resurrecting a session
the user deleted because a device that had been in a tunnel disagreed — is the worse failure, and
the one users actually report as a bug. `TwoDeviceConvergenceTest` encodes this exact sequence, and
its counterpart proves that an *explicit* restore written after the delete (newer `updatedAt`,
`deleted = false`) does bring the session back everywhere — deletion is final, but not
irreversible.

## Consequences

- A change made in flight mode is never lost: it is durable and queued in the same commit, and it
  leaves the device the next time WorkManager sees a network, with the app closed if need be.
- Two devices converge to the same state regardless of the order in which changes arrive, and the
  test that says so runs two real Room databases rather than asserting on a mock.
- A deleted session stays deleted. The queue cleanup in `applyPage` is what makes that true on the
  second round trip, not just the first.
- The user can see whether sync is working — pending count, last successful sync, last error — and
  can force a run. Until this existed, a device that had been failing for a week looked exactly
  like a healthy one.
- `updatedAt` for sync is read from the stored `study_sessions.updated_at` column rather than from
  the projection, which derives it from the last event's wall clock. Without this, a metadata-only
  correction (a note, a subject, a deletion — none of which append an event) would carry a stale
  timestamp and could never win a conflict. This was a real bug, caught by the convergence test.
- `deviceId` as a tie-break means a device with a lexicographically higher id systematically wins
  exact ties. That is an acceptable, documented bias: ties require identical millisecond
  timestamps, and determinism is worth more here than fairness.
- Tasks, subjects and materials still do not sync. That is a known gap with a deliberate reason
  (no protocol), not an omission — and the queue is shaped so that closing it is additive.
- The server side of the protocol is specified in `docs/api/openapi.yaml` but not implemented in
  this repository; `FakeStudyFlowBackend` is what the tests run against, and it implements the
  contract as written.
