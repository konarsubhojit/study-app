package dev.studyflow.core.domain.timer

import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.TimeAnchor
import kotlin.time.Duration

/**
 * The state a session's event log folds down to.
 *
 * Deliberately *derived*, never stored. There is no `elapsedMillis` column anywhere in the app, so
 * there is nothing to forget to update, nothing to lose when the process is killed, and nothing to
 * drift out of sync with reality. The only durable data is the append-only [SessionEvent] log.
 */
public sealed interface TimerState {
    /** No session has been started, or the previous one was cleared away. */
    public data object Idle : TimerState

    /** A session exists. */
    public sealed interface Active : TimerState {
        public val sessionId: String

        /** Time from intervals that are closed and were measured within a single boot. */
        public val settled: Duration

        /** Time across a boundary the monotonic clock could not bridge; see [SessionElapsed]. */
        public val unverified: Duration

        /** Sequence number of the last event in the log, so the next append is ordered correctly. */
        public val lastSequence: Long
    }

    /** Counting right now. [openedAt] is the anchor of the `STARTED`/`RESUMED` that opened it. */
    public data class Running(
        override val sessionId: String,
        override val settled: Duration,
        override val unverified: Duration,
        override val lastSequence: Long,
        val openedAt: TimeAnchor,
    ) : Active

    /** Not counting, but resumable. */
    public data class Paused(
        override val sessionId: String,
        override val settled: Duration,
        override val unverified: Duration,
        override val lastSequence: Long,
    ) : Active

    /** Finished. The log is closed and the session can be projected into history. */
    public data class Stopped(
        override val sessionId: String,
        override val settled: Duration,
        override val unverified: Duration,
        override val lastSequence: Long,
    ) : Active
}

/** What the user asked the timer to do. */
public sealed interface TimerCommand {
    /** Begin a brand-new session with its own event log. */
    public data class Start(
        val sessionId: String,
        val subjectId: String? = null,
        val note: String? = null,
    ) : TimerCommand

    /** Close the open interval. */
    public data object Pause : TimerCommand

    /** Open a new interval after a pause. */
    public data object Resume : TimerCommand

    /** Close the open interval, if any, and end the session. */
    public data object Stop : TimerCommand
}

/** Outcome of a [TimerCommand]. */
public sealed interface TimerCommandResult {
    /**
     * The command was legal. [event] must be appended to the log durably *before* [state] is
     * treated as current, so that a process death between the two leaves the log authoritative.
     */
    public data class Accepted(
        val event: SessionEvent,
        val state: TimerState.Active,
    ) : TimerCommandResult

    /** The command made no sense in the current state; nothing was produced. */
    public data class Rejected(
        val reason: TimerRejection,
    ) : TimerCommandResult
}

/** Why a [TimerCommand] was refused. Rejections are normal control flow, not exceptions. */
public enum class TimerRejection {
    /** `Start` while a session is already running or paused. */
    SESSION_ALREADY_ACTIVE,

    /** `Pause` when nothing is running. */
    NOT_RUNNING,

    /** `Resume` when the session is not paused. */
    NOT_PAUSED,

    /** `Pause`/`Resume`/`Stop` with no session at all. */
    NO_ACTIVE_SESSION,

    /** Any command against a session that has already been stopped. */
    SESSION_ALREADY_STOPPED,
}
