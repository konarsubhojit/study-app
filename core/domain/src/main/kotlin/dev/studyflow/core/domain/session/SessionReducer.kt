package dev.studyflow.core.domain.session

import dev.studyflow.core.domain.timer.TimerEngine
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.SessionEventType
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import kotlin.time.Duration

/**
 * The attributes of a session that the event log does not carry.
 *
 * `STARTED`/`PAUSED`/`RESUMED`/`STOPPED` describe *timing*; a subject or a note is editable
 * metadata that would be meaningless to replay. Keeping them out of the log means renaming a
 * session can never disturb a measurement.
 *
 * @property deviceId the device that owns the log; `elapsedRealtime` anchors are only comparable
 *   within one device, and it is the scope of the "one active session" invariant.
 */
public data class SessionDescriptor(
    val id: String,
    val deviceId: String,
    val taskId: String? = null,
    val subjectId: String? = null,
    val note: String? = null,
    val deleted: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "SessionDescriptor.id must not be blank" }
        require(deviceId.isNotBlank()) { "SessionDescriptor.deviceId must not be blank" }
    }
}

/**
 * The reduction `events -> session state`, as a pure function.
 *
 * This is the only place a [StudySession] row is ever derived from, which is what makes the
 * persisted projection disposable: if a process dies after the event was committed but before the
 * projection was written, replaying the log produces exactly the row that was lost. The projection
 * is a cache of this function, never a second source of truth.
 */
public object SessionReducer {
    /**
     * Folds a session's whole log into the flat projection.
     *
     * [StudySession.elapsed] reports only time that is *settled* — intervals closed by a `PAUSED`
     * or `STOPPED` event. The interval that is currently open has no end yet, so its length depends
     * on when you ask; callers that want a live total read it with [TimerEngine.elapsedAt] rather
     * than persisting a number that is stale the instant it is written.
     *
     * @param descriptor metadata that lives outside the log.
     * @param events the session's events, in any order; ordering is taken from
     *   [SessionEvent.sequence].
     * @return the projection, or `null` when the log is empty — a session only exists once it has
     *   been started.
     * @throws IllegalArgumentException if [events] belong to a session other than the descriptor's.
     */
    public fun reduce(
        descriptor: SessionDescriptor,
        events: List<SessionEvent>,
    ): StudySession? {
        if (events.isEmpty()) return null
        val ordered = events.sortedBy { it.sequence }
        require(ordered.all { it.sessionId == descriptor.id }) {
            "events belong to ${ordered.map { it.sessionId }.distinct()}, expected ${descriptor.id}"
        }

        val state = TimerEngine.fold(ordered)
        return StudySession(
            id = descriptor.id,
            taskId = descriptor.taskId,
            subjectId = descriptor.subjectId,
            note = descriptor.note,
            startedAt = ordered.first().anchor.wallClock,
            endedAt = ordered.lastOrNull { it.type == SessionEventType.STOPPED }?.anchor?.wallClock,
            status = state.asStatus(),
            elapsed = SessionElapsed(counted = state.settled(), unverified = state.unverified()),
            deviceId = descriptor.deviceId,
            updatedAt = ordered.last().anchor.wallClock,
            deleted = descriptor.deleted,
        )
    }

    // A non-empty log never folds to `Idle`; it is mapped alongside `Paused` because both mean
    // "the session exists and is not counting".
    private fun TimerState.asStatus(): SessionStatus =
        when (this) {
            is TimerState.Running -> SessionStatus.RUNNING
            is TimerState.Stopped -> SessionStatus.STOPPED
            is TimerState.Paused, TimerState.Idle -> SessionStatus.PAUSED
        }

    private fun TimerState.settled(): Duration = (this as? TimerState.Active)?.settled ?: Duration.ZERO

    private fun TimerState.unverified(): Duration = (this as? TimerState.Active)?.unverified ?: Duration.ZERO
}
