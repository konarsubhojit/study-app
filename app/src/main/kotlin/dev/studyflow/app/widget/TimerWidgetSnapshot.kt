package dev.studyflow.app.widget

import dev.studyflow.core.domain.timer.TimerEngine
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.TimeAnchor
import kotlin.time.Duration

/** The one-of-three state a widget or the Quick Settings tile renders. */
internal enum class TimerWidgetPhase {
    IDLE,
    RUNNING,
    PAUSED,
}

/**
 * Everything the timer widget and the Quick Settings tile draw, folded from the event log once.
 *
 * Deliberately free of Android types so the mapping can be asserted on the JVM, and deliberately a
 * *snapshot*: nothing here ticks. [chronometerBaseMillis] is what makes a running widget count up
 * without the app doing any work — it is handed to the platform's own `Chronometer`, which keeps
 * drawing seconds in the launcher's process long after this process is gone (see
 * docs/adr/0003-timer-event-sourcing.md for why the app never runs a ticker of its own).
 */
internal data class TimerWidgetSnapshot(
    val phase: TimerWidgetPhase,
    val elapsed: Duration,
    val hasUnverifiedTime: Boolean,
    /** The `SystemClock.elapsedRealtime()` reading the current elapsed time started from. */
    val chronometerBaseMillis: Long,
    val note: String? = null,
) {
    val isRunning: Boolean get() = phase == TimerWidgetPhase.RUNNING

    val isActive: Boolean get() = phase != TimerWidgetPhase.IDLE

    internal companion object {
        val Idle: TimerWidgetSnapshot =
            TimerWidgetSnapshot(
                phase = TimerWidgetPhase.IDLE,
                elapsed = Duration.ZERO,
                hasUnverifiedTime = false,
                chronometerBaseMillis = 0,
            )
    }
}

/**
 * Folds [this] into what a widget shows at [now].
 *
 * A stopped session is reported as [TimerWidgetPhase.IDLE]: its log is closed, so the only control
 * that still makes sense on the home screen is "start a new one".
 */
internal fun TimerState.toWidgetSnapshot(
    now: TimeAnchor,
    note: String? = null,
): TimerWidgetSnapshot {
    val phase =
        when (this) {
            TimerState.Idle, is TimerState.Stopped -> TimerWidgetPhase.IDLE
            is TimerState.Running -> TimerWidgetPhase.RUNNING
            is TimerState.Paused -> TimerWidgetPhase.PAUSED
        }
    if (phase == TimerWidgetPhase.IDLE) return TimerWidgetSnapshot.Idle

    val elapsed = TimerEngine.elapsedAt(this, now)
    return TimerWidgetSnapshot(
        phase = phase,
        elapsed = elapsed.counted,
        hasUnverifiedTime = elapsed.unverified > Duration.ZERO,
        chronometerBaseMillis = (now.uptime - elapsed.counted).inWholeMilliseconds,
        note = note?.takeIf(String::isNotBlank),
    )
}

/** `1:04:09` once an hour has passed, `04:09` before that — the shape a stopwatch is read in. */
internal fun formatElapsed(elapsed: Duration): String =
    elapsed.toComponents { hours, minutes, seconds, _ ->
        if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }
