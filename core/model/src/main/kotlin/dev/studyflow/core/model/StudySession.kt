package dev.studyflow.core.model

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A study session as the user thinks about it: a subject, a start, and time spent.
 *
 * This is a *projection* of the session's [SessionEvent] log, never the source of truth. It exists
 * so the UI and statistics have something flat to render, and it can be rebuilt from scratch at any
 * point by replaying the log. Note that [elapsed] is therefore derived, not stored: there is no
 * accumulated-milliseconds field anywhere that a missed write could leave permanently wrong.
 *
 * @property deviceId the device whose event log produced this projection. Sessions are measured
 *   per device — an `elapsedRealtime` anchor from another phone means nothing here — and it is the
 *   scope the "at most one active session" invariant is enforced in.
 * @property updatedAt when the projection was last rewritten, used for sync conflict resolution.
 * @property deleted soft-delete marker; rows are tombstoned rather than removed so a deletion can
 *   still be replicated to other devices.
 */
public data class StudySession(
    val id: String,
    val subjectId: String?,
    val note: String?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val status: SessionStatus,
    val elapsed: SessionElapsed,
    val deviceId: String,
    val updatedAt: Instant,
    val deleted: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "StudySession.id must not be blank" }
        require(deviceId.isNotBlank()) { "StudySession.deviceId must not be blank" }
    }

    /** True while the session still accepts commands, i.e. it is running or paused. */
    public val isActive: Boolean get() = !deleted && status != SessionStatus.STOPPED
}

/** The state a session's event log folds down to. */
public enum class SessionStatus {
    /** Started or resumed, currently counting. */
    RUNNING,

    /** Paused by the user, or auto-paused by recovery after an unverifiable gap. */
    PAUSED,

    /** Finished; the log is closed. */
    STOPPED,
}

/**
 * Time attributed to a session, split by how much the app can actually vouch for it.
 *
 * Keeping [unverified] separate is a deliberate honesty guarantee: if the device rebooted while the
 * timer was running, the app genuinely cannot tell whether the user studied for two hours or the
 * phone sat switched off in a bag. Folding that guess into [counted] would quietly fabricate study
 * data, so instead it is surfaced and the user decides.
 *
 * @property counted time measured against the monotonic clock within a single boot. Trustworthy.
 * @property unverified time that elapsed on the wall clock across a boundary the monotonic clock
 *   could not bridge. Never included in [counted] and never reported as study time until the user
 *   confirms it.
 */
public data class SessionElapsed(
    val counted: Duration,
    val unverified: Duration = Duration.ZERO,
) {
    init {
        require(!counted.isNegative()) { "counted elapsed must not be negative, was $counted" }
        require(!unverified.isNegative()) { "unverified elapsed must not be negative, was $unverified" }
    }

    /** True when part of this session's time needs a human decision before it can be trusted. */
    public val hasUnverifiedTime: Boolean get() = unverified > Duration.ZERO

    /** Upper bound on the session length: everything measured plus everything unaccounted for. */
    public val optimisticTotal: Duration get() = counted + unverified

    public companion object {
        public val ZERO: SessionElapsed = SessionElapsed(Duration.ZERO, Duration.ZERO)
    }
}
