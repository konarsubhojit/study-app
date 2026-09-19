package dev.studyflow.core.domain.sync

import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.StudySession
import kotlin.time.Instant

/**
 * What a queued change refers to (issue #55).
 *
 * Only completed study sessions are replicated today — they are the only entity
 * `docs/api/openapi.yaml` describes a write for — but the queue carries the type so that a second
 * syncable entity becomes a new constant and a new branch rather than a second table.
 */
public enum class SyncEntityType {
    SESSION,
}

/** Whether a queued change upserts the entity remotely or tombstones it. */
public enum class SyncOperation {
    UPSERT,
    DELETE,
}

/**
 * One outbound change, written in the *same* Room transaction as the mutation that caused it.
 *
 * The row records which entity changed rather than a serialized copy of it: the drain reads the
 * current row when it sends, so a queue entry can never ship a stale version of a session, and
 * repeated edits of one entity coalesce into the single pending entry the user sees counted.
 *
 * @property sequence insertion order, and the handle the drain acknowledges. A mutation arriving
 *   while a batch is in flight replaces the entry under a *new* sequence, so acknowledging the old
 *   one cannot discard it.
 * @property updatedAt the mutation's `updatedAt`, used to decide whether an inbound change
 *   supersedes this still-pending local one.
 */
public data class SyncQueueItem(
    val sequence: Long,
    val entityType: SyncEntityType,
    val entityId: String,
    val operation: SyncOperation,
    val updatedAt: Instant,
    val deviceId: String,
)

/**
 * A session as it travels between devices: the projection plus its append-only event log.
 *
 * Both halves travel together because the log is the source of truth and the projection is only a
 * cache of [dev.studyflow.core.domain.session.SessionReducer] over it — shipping one without the
 * other would force the receiver to invent the missing half.
 */
public data class SyncSessionRecord(
    val session: StudySession,
    val events: List<SessionEvent>,
) {
    init {
        require(events.all { it.sessionId == session.id }) {
            "every event must belong to session ${session.id}"
        }
    }
}

/** One page of the inbound delta, as the server answered it. */
public data class SyncPage(
    val changes: List<SyncSessionRecord>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

/** A failed sync attempt, in the two terms the caller can act on. */
public data class SyncFailure(
    val message: String,
    /** `false` for a failure another attempt cannot fix, such as a rejected payload. */
    val retryable: Boolean = true,
)

/** What the server did with a pushed batch. */
public data class SyncPushAck(
    val acceptedIds: Set<String>,
    /**
     * Changes the server refused because it holds a strictly newer state, typically a tombstone.
     * They leave the queue all the same: the delta pull delivers the winning version.
     */
    val rejectedIds: Set<String> = emptySet(),
)

/** Either a transport value, or the failure that replaced it. */
public sealed interface SyncResult<out T> {
    public data class Success<T>(
        val value: T,
    ) : SyncResult<T>

    public data class Failure(
        val failure: SyncFailure,
    ) : SyncResult<Nothing>
}

/** Why a sync ran, which is what decides whether it may spend a request on a pull. */
public enum class SyncTrigger {
    /** The user tapped "Sync now": always pulls, because the user asked to be up to date. */
    MANUAL,

    /** A periodic or connectivity-triggered run: pulls, because nothing else would. */
    SCHEDULED,

    /** A local mutation asked for its queue to be drained: never pulls for its own sake. */
    OUTBOUND,
}

/** What one [SyncEngine.sync] call did. */
public sealed interface SyncOutcome {
    /** Nothing to send and nobody asked for a pull — not a byte left the device. */
    public data object Idle : SyncOutcome

    public data class Synced(
        val pushed: Int,
        val applied: Int,
    ) : SyncOutcome

    public data class Failed(
        val failure: SyncFailure,
    ) : SyncOutcome
}

/**
 * What the settings screen shows: how far behind the device is, when it was last in step, and what
 * went wrong the last time it tried.
 */
public data class SyncStatus(
    val pendingCount: Int = 0,
    val lastSuccessAt: Instant? = null,
    val lastError: String? = null,
    val lastAttemptAt: Instant? = null,
) {
    /** True when every local change has been accepted and the last attempt did not fail. */
    public val isUpToDate: Boolean get() = pendingCount == 0 && lastError == null
}
