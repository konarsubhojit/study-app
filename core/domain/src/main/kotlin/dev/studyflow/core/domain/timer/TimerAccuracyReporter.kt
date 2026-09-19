package dev.studyflow.core.domain.timer

import kotlin.time.Duration

/**
 * Boundary for reporting timer accuracy telemetry.
 *
 * The timer's source of truth remains the event log; this hook exists only so analytics can surface
 * field regressions when the wall clock, notification chronometer or recovery path disagree with the
 * monotonic measurement.
 */
public interface TimerAccuracyReporter {
    public fun report(sample: TimerAccuracySample)

    public companion object NoOp : TimerAccuracyReporter {
        override fun report(sample: TimerAccuracySample): Unit = Unit
    }
}

public data class TimerAccuracySample(
    val sessionId: String,
    val source: TimerAccuracySource,
    val expectedElapsed: Duration,
    val measuredElapsed: Duration,
    val drift: Duration = measuredElapsed - expectedElapsed,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(!expectedElapsed.isNegative()) { "expectedElapsed must not be negative" }
        require(!measuredElapsed.isNegative()) { "measuredElapsed must not be negative" }
    }
}

public enum class TimerAccuracySource {
    FOREGROUND_REFRESH,
    REBOOT_RECOVERY,
}
