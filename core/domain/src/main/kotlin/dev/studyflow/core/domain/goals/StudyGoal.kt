package dev.studyflow.core.domain.goals

import kotlin.time.Duration

/** How often a [StudyGoal]'s target resets (issue #73). */
public enum class GoalPeriod {
    /** Resets at local midnight; the target applies to the current calendar day. */
    DAILY,

    /** Resets at the start of the local ISO week (Monday); matches the statistics screen's week. */
    WEEKLY,
}

/**
 * What a [StudyGoal] is measured against: every counted minute, or just one subject's.
 *
 * A sealed interface rather than a nullable `subjectId` so "overall" is a real, exhaustive case
 * instead of a magic `null` that every caller has to remember to check for.
 */
public sealed interface GoalScope {
    /** Counts every session, regardless of subject. */
    public data object Overall : GoalScope

    /** Counts only sessions for [subjectId]. */
    public data class BySubject(
        val subjectId: String,
    ) : GoalScope {
        init {
            require(subjectId.isNotBlank()) { "subjectId must not be blank" }
        }
    }
}

/**
 * A user-defined study target (issue #73).
 *
 * Purely descriptive: creating one does not schedule anything or send a notification by itself —
 * see [dev.studyflow.core.domain.nudges.NudgePolicy] for the opt-in, rate-limited layer that turns
 * progress towards a goal into an actual nudge.
 *
 * @property target must be positive; a goal of zero would already be "met" before any studying
 *   happened, which is meaningless as a target.
 */
public data class StudyGoal(
    val period: GoalPeriod,
    val scope: GoalScope,
    val target: Duration,
) {
    init {
        require(target.isPositive()) { "target must be positive, was $target" }
    }
}
