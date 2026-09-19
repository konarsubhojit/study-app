package dev.studyflow.core.domain.streaks

import kotlinx.datetime.LocalDate

/**
 * How many local calendar days in a row [dev.studyflow.core.model.StudySession]s were counted on,
 * as of the moment [dev.studyflow.core.domain.streaks.StreakCalculator.compute] was called
 * (issue #73).
 *
 * See [StreakCalculator]'s KDoc for the exact, documented rules a "study day" and a streak follow.
 *
 * @property currentStreak consecutive qualifying days, counting backwards from today (or, if today
 *   has not yet had a qualifying day, from yesterday — see [StreakCalculator]).
 * @property longestStreak the longest run [currentStreak] has ever reached across all history.
 * @property lastStudyDay the most recent day that qualified as a study day, or `null` if none ever
 *   have.
 * @property graceDaysAllowed the grace-day budget the streak was computed with, carried along so a
 *   UI can render "1 of 2 grace days used" without recomputing the streak itself.
 * @property graceDaysUsedInCurrentRun how many of [graceDaysAllowed] are currently spent on the gap
 *   trailing [currentStreak]; `0` when the most recent relevant day was itself a study day.
 */
public data class StreakSummary(
    val currentStreak: Int,
    val longestStreak: Int,
    val lastStudyDay: LocalDate?,
    val graceDaysAllowed: Int,
    val graceDaysUsedInCurrentRun: Int,
) {
    init {
        require(currentStreak >= 0) { "currentStreak must not be negative, was $currentStreak" }
        require(longestStreak >= currentStreak) {
            "longestStreak ($longestStreak) must be at least currentStreak ($currentStreak)"
        }
        require(graceDaysAllowed >= 0) { "graceDaysAllowed must not be negative, was $graceDaysAllowed" }
        require(graceDaysUsedInCurrentRun in 0..graceDaysAllowed) {
            "graceDaysUsedInCurrentRun ($graceDaysUsedInCurrentRun) must be within 0..$graceDaysAllowed"
        }
    }
}
