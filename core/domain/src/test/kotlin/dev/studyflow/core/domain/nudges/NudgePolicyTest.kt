package dev.studyflow.core.domain.nudges

import dev.studyflow.core.domain.goals.GoalPeriod
import dev.studyflow.core.domain.goals.GoalProgress
import dev.studyflow.core.domain.goals.GoalProgressState
import dev.studyflow.core.domain.goals.GoalScope
import dev.studyflow.core.domain.goals.StudyGoal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Covers issue #73's acceptance criteria that every nudge can be disabled in one place and
 * defaults are conservative, plus the rate limit that keeps an opt-in nudge from ever spamming.
 */
@DisplayName("NudgePolicy")
class NudgePolicyTest {
    private val utc = TimeZone.UTC

    @Test
    fun `defaults never produce a nudge`() {
        val nudges =
            NudgePolicy.evaluate(
                settings = NudgeSettings(),
                now = localNow(hour = 21),
                dailyOverallGoalProgress = almostMetProgress(),
                lastSentOn = emptyMap(),
            )

        assertTrue(nudges.isEmpty(), "the conservative default (everything false) must send nothing")
    }

    @Test
    fun `the master switch disables every nudge even when per-type toggles are on`() {
        val settings =
            NudgeSettings(
                nudgesEnabled = false,
                endOfDaySummaryEnabled = true,
                goalAlmostReachedEnabled = true,
            )

        val nudges =
            NudgePolicy.evaluate(
                settings = settings,
                now = localNow(hour = 21),
                dailyOverallGoalProgress = almostMetProgress(),
                lastSentOn = emptyMap(),
            )

        assertTrue(nudges.isEmpty(), "master switch off must override every per-type toggle")
    }

    @Test
    fun `goal-almost-reached fires only when enabled and genuinely close`() {
        val settings = NudgeSettings(nudgesEnabled = true, goalAlmostReachedEnabled = true)

        val nudges =
            NudgePolicy.evaluate(
                settings = settings,
                now = localNow(hour = 12),
                dailyOverallGoalProgress = almostMetProgress(),
                lastSentOn = emptyMap(),
            )

        assertEquals(1, nudges.size)
        assertEquals(NudgeType.GOAL_ALMOST_REACHED, nudges.single().type)
    }

    @Test
    fun `goal-almost-reached does not fire once the goal is already met`() {
        val settings = NudgeSettings(nudgesEnabled = true, goalAlmostReachedEnabled = true)
        val met =
            GoalProgress(
                goal = StudyGoal(GoalPeriod.DAILY, GoalScope.Overall, target = 30.minutes),
                achieved = 30.minutes,
                windowStart = Instant.parse("2026-03-10T00:00:00Z"),
                windowEnd = Instant.parse("2026-03-11T00:00:00Z"),
                state = GoalProgressState.MET,
            )

        val nudges =
            NudgePolicy.evaluate(
                settings = settings,
                now = localNow(hour = 12),
                dailyOverallGoalProgress = met,
                lastSentOn = emptyMap(),
            )

        assertTrue(nudges.isEmpty(), "a met goal has nothing left to nudge towards")
    }

    @Test
    fun `end-of-day summary only fires in the evening`() {
        val settings = NudgeSettings(nudgesEnabled = true, endOfDaySummaryEnabled = true)

        val daytime =
            NudgePolicy.evaluate(
                settings,
                localNow(hour = 12),
                dailyOverallGoalProgress = null,
                lastSentOn = emptyMap(),
            )
        val evening =
            NudgePolicy.evaluate(
                settings,
                localNow(hour = 21),
                dailyOverallGoalProgress = null,
                lastSentOn = emptyMap(),
            )

        assertTrue(daytime.isEmpty(), "no end-of-day summary before the configured hour")
        assertEquals(1, evening.size)
        assertEquals(NudgeType.END_OF_DAY_SUMMARY, evening.single().type)
    }

    @Test
    fun `each nudge type fires at most once per local day`() {
        val settings = NudgeSettings(nudgesEnabled = true, endOfDaySummaryEnabled = true)
        val today = localNow(hour = 21).date
        val alreadySentToday = mapOf(NudgeType.END_OF_DAY_SUMMARY to today)

        val nudges =
            NudgePolicy.evaluate(
                settings = settings,
                now = localNow(hour = 23),
                dailyOverallGoalProgress = null,
                lastSentOn = alreadySentToday,
            )

        assertTrue(nudges.isEmpty(), "already sent today; must not fire again before tomorrow")
    }

    @Test
    fun `a nudge sent on a previous day is eligible again today`() {
        val settings = NudgeSettings(nudgesEnabled = true, endOfDaySummaryEnabled = true)
        val yesterday = LocalDate(2026, 3, 9)
        val sentYesterday = mapOf(NudgeType.END_OF_DAY_SUMMARY to yesterday)

        val nudges =
            NudgePolicy.evaluate(
                settings = settings,
                now = localNow(hour = 21),
                dailyOverallGoalProgress = null,
                lastSentOn = sentYesterday,
            )

        assertEquals(1, nudges.size, "a new local day resets the rate limit")
    }

    private fun almostMetProgress(): GoalProgress =
        GoalProgress(
            goal = StudyGoal(GoalPeriod.DAILY, GoalScope.Overall, target = 1.hours),
            achieved = 50.minutes,
            windowStart = Instant.parse("2026-03-10T00:00:00Z"),
            windowEnd = Instant.parse("2026-03-11T00:00:00Z"),
            state = GoalProgressState.ON_TRACK,
        )

    /** 2026-03-10 at [hour]:00, matching [almostMetProgress]'s window. */
    private fun localNow(hour: Int): LocalDateTime =
        (Instant.parse("2026-03-10T00:00:00Z") + hour.hours).toLocalDateTime(utc)
}
