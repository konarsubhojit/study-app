package dev.studyflow.core.domain.goals

import kotlin.time.Duration
import kotlin.time.Instant

/** Where a [GoalProgress] currently stands relative to its pace, not just its final total. */
public enum class GoalProgressState {
    /** [GoalProgress.achieved] has already reached [StudyGoal.target] for this window. */
    MET,

    /**
     * Behind [StudyGoal.target] but at or ahead of the pace needed to reach it by the window's
     * end, assuming steady effort from here — no dark-pattern urgency implied, just a pace check.
     */
    ON_TRACK,

    /** Behind both the target and the pace needed to reach it on current effort. */
    BEHIND,
}

/**
 * A [StudyGoal]'s progress for the window ([windowStart], [windowEnd]) that contains "now".
 *
 * @property achieved counted study time within the window, computed via the exact same
 *   [dev.studyflow.core.domain.stats.StatsAggregator] bucketing the statistics screen uses (see
 *   [GoalProgressCalculator]), so this number is never a second, possibly-divergent total.
 * @property windowStart the window's first instant, at local midnight/the local week's Monday in
 *   the zone progress was computed in — half-open with [windowEnd], like `StatsRange`.
 */
public data class GoalProgress(
    val goal: StudyGoal,
    val achieved: Duration,
    val windowStart: Instant,
    val windowEnd: Instant,
    val state: GoalProgressState,
) {
    init {
        require(windowStart < windowEnd) { "windowStart ($windowStart) must be before windowEnd ($windowEnd)" }
    }

    /** How much more counted time is needed to reach [StudyGoal.target]; never negative. */
    public val remaining: Duration get() = (goal.target - achieved).coerceAtLeast(Duration.ZERO)
}
