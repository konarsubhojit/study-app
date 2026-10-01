package dev.studyflow.core.domain.sync

import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.model.StudyTask
import kotlin.time.Instant

/**
 * What a queued change refers to (issues #55 and #7).
 *
 * Completed study sessions travel on their own stream because they carry an event log; tasks and
 * catalogued materials travel as [SyncDocument]s on the record stream (ADR 0018). Subjects and
 * folders are not user-editable yet and so are not replicated.
 */
public enum class SyncEntityType {
    SESSION,
    TASK,
    MATERIAL,
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

/**
 * A task or material as it travels between devices (ADR 0018): the whole aggregate, plus the
 * last-write-wins metadata the conflict rule compares.
 *
 * Only replicated state is carried. Device-local state — a reminder's platform alarm, snooze and
 * fired marker, a material's downloaded file, offline pin and preview position — is stripped by
 * [SyncDocumentCodec] and kept from the receiving device's own copy by [DocumentSyncMerge].
 */
public sealed interface SyncDocument {
    public val id: String
    public val entityType: SyncEntityType

    /** The device that made the write this copy reflects; the last-write-wins tie-break. */
    public val deviceId: String
    public val updatedAt: Instant
    public val deleted: Boolean
}

public data class SyncTaskRecord(
    val task: StudyTask,
    override val deviceId: String,
) : SyncDocument {
    override val id: String get() = task.id
    override val entityType: SyncEntityType get() = SyncEntityType.TASK
    override val updatedAt: Instant get() = task.updatedAt
    override val deleted: Boolean get() = task.deleted
}

public data class SyncMaterialRecord(
    val material: Material,
    override val deviceId: String,
) : SyncDocument {
    override val id: String get() = material.id
    override val entityType: SyncEntityType get() = SyncEntityType.MATERIAL
    override val updatedAt: Instant get() = material.updatedAt
    override val deleted: Boolean get() = material.deleted
}

/** One page of the inbound record delta. */
public data class SyncDocumentPage(
    val changes: List<SyncDocument>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

/** One page of the inbound delta, as the server answered it. */
public data class SyncPage(
    val changes: List<SyncSessionRecord>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

/** A failed sync attempt, in the terms the caller can act on. */
public data class SyncFailure(
    val message: String,
    /** `false` for a failure another attempt cannot fix, such as a rejected payload. */
    val retryable: Boolean = true,
    /**
     * Why it failed, as a closed set safe to log in a release build.
     *
     * [message] is user-facing copy and is discarded by the release log sanitiser; this is the
     * part a bug report needs, and it is an enum precisely so it can never carry an exception's
     * text or a URL.
     */
    val reason: SyncFailureReason = SyncFailureReason.UNEXPECTED,
)

/** The cause of a [SyncFailure], transport-neutral and free of anything user-specific. */
public enum class SyncFailureReason {
    /** The request never left the device: no connectivity, DNS failure, refused connection. */
    OFFLINE,
    TIMEOUT,

    /** Credentials missing, expired beyond refresh, or rejected — a failure before any useful work. */
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    RATE_LIMITED,
    SERVER,
    UPGRADE_REQUIRED,

    /** The answer could not be read as the protocol this version speaks. */
    MALFORMED,
    UNEXPECTED,
}

/** The half of a sync pass a failure happened in. */
public enum class SyncStage {
    /** Draining the outbound queue. */
    PUSH,

    /** Reading the inbound delta. */
    PULL,
}

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
        val recordsPushed: Int = 0,
        val recordsPulled: Int = 0,
        val recordCursorAdvanced: Boolean = false,
    ) : SyncOutcome

    public data class Failed(
        val failure: SyncFailure,
        /** Which half of the pass failed, so a log can say whether anything was sent. */
        val stage: SyncStage = SyncStage.PUSH,
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
