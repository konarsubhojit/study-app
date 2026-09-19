package dev.studyflow.core.domain.sync

import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession

/** What merging one inbound change into the local copy decided. */
public sealed interface SessionMergeOutcome {
    /**
     * The change must not be stored at all.
     *
     * Today that means one thing: a session that is still running or paused. A running stopwatch
     * belongs to exactly one device — replicating it would let two devices fight over one timer —
     * so an active session is refused rather than merged, wherever it came from.
     */
    public data class Rejected(
        val reason: String,
    ) : SessionMergeOutcome

    /**
     * The state both devices hold once this change is stored.
     *
     * @property remoteWon true when the inbound copy beat the local one on the last-write-wins
     *   rule, which is also what tells the caller that a still-pending local change for the same
     *   session has been superseded.
     */
    public data class Merged(
        val record: SyncSessionRecord,
        val remoteWon: Boolean,
    ) : SessionMergeOutcome
}

/**
 * The conflict policy, as a pure function of the two copies (issue #55).
 *
 * Three rules, in this order:
 *
 * 1. **Metadata is last-write-wins** on [StudySession.updatedAt], with [StudySession.deviceId] as
 *    the tie-break and "deleted beats alive" as the tie-break of the tie-break. Every term is data
 *    both devices already hold, so both compute the same winner without talking to each other —
 *    which is what makes convergence deterministic rather than a function of arrival order.
 * 2. **The event log is unioned by id and never overwritten.** Events are append-only facts about
 *    what the user did; keeping the local copy of an id that exists on both sides means a
 *    replayed, reordered or duplicated delivery cannot rewrite a measurement.
 * 3. **A tombstone is a row, not an absence.** A deleted session keeps its row and its log, so a
 *    later delivery of an *older* edit loses the comparison in rule 1 instead of recreating the
 *    session. Deleting rows outright would make resurrection the default.
 */
public object SessionSyncMerge {
    /** Whether a session may cross the wire at all; only a finished one may. */
    public fun isSyncable(session: StudySession): Boolean = session.status == SessionStatus.STOPPED

    /**
     * Merges [remote] into [local], or explains why it was refused.
     *
     * @param local the copy this device holds, or `null` when it has never seen the session.
     */
    public fun merge(
        local: SyncSessionRecord?,
        remote: SyncSessionRecord,
    ): SessionMergeOutcome {
        if (!isSyncable(remote.session)) {
            return SessionMergeOutcome.Rejected(
                "session ${remote.session.id} is ${remote.session.status}; active sessions are never synced",
            )
        }
        if (local == null) {
            return SessionMergeOutcome.Merged(
                record = SyncSessionRecord(remote.session, remote.events.ordered()),
                remoteWon = true,
            )
        }

        val remoteWon = wins(candidate = remote.session, incumbent = local.session)
        return SessionMergeOutcome.Merged(
            record =
                SyncSessionRecord(
                    session = if (remoteWon) remote.session else local.session,
                    events = mergeEvents(local.events, remote.events),
                ),
            remoteWon = remoteWon,
        )
    }

    /**
     * The last-write-wins comparison, spelled out so it can be tested on its own.
     *
     * @return true when [candidate] replaces [incumbent].
     */
    public fun wins(
        candidate: StudySession,
        incumbent: StudySession,
    ): Boolean =
        when {
            candidate.updatedAt != incumbent.updatedAt -> candidate.updatedAt > incumbent.updatedAt

            candidate.deviceId != incumbent.deviceId -> candidate.deviceId > incumbent.deviceId

            // Same device, same instant: the two copies describe the same write — unless one is a
            // tombstone, and letting that win is what stops a delete being undone by a stale copy
            // of the row it deleted.
            else -> candidate.deleted && !incumbent.deleted
        }

    /**
     * Unions two logs by [SessionEvent.id], keeping the copy this device already stored.
     *
     * "Never overwrite" is the point: an event is a recorded fact, and a second delivery of it —
     * however the wire mangled it — must not move a timestamp that statistics already counted.
     */
    public fun mergeEvents(
        local: List<SessionEvent>,
        remote: List<SessionEvent>,
    ): List<SessionEvent> {
        val known = local.associateByTo(LinkedHashMap(), SessionEvent::id)
        remote.forEach { event -> known.putIfAbsent(event.id, event) }
        return known.values.toList().ordered()
    }

    /** Sequence order, with the id as a stable tie-break so both devices list events identically. */
    private fun List<SessionEvent>.ordered(): List<SessionEvent> = sortedWith(compareBy({ it.sequence }, { it.id }))
}
