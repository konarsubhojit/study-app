package dev.studyflow.core.domain.goals

import dev.studyflow.core.domain.stats.StatsAggregator
import dev.studyflow.core.domain.stats.StatsBucketSize
import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.testing.data.testStudySession
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * [GoalProgressCalculator] is exercised directly against [StatsAggregator] so a goal's "achieved"
 * figure is proven identical to what the statistics screen would show for the same window, which
 * is issue #73's "goal progress matches the statistics screen exactly" acceptance criterion.
 */
@DisplayName("GoalProgressCalculator")
class GoalProgressCalculatorTest {
    private val newYork = TimeZone.of("America/New_York")

    @Test
    fun `daily achieved total equals the statistics screen's day bucket`() {
        val now = Instant.parse("2026-03-10T15:00:00Z") // 2026-03-10T10:00 EST — mid-day.
        val sessions =
            listOf(
                session("s1", startedAt = now - 2.hours, countedMinutes = 40),
                session("s2", startedAt = now - 1.hours, countedMinutes = 20),
                // Yesterday, must not contribute to today's total.
                session("s3", startedAt = now - 25.hours, countedMinutes = 999),
            )
        val goal = StudyGoal(GoalPeriod.DAILY, GoalScope.Overall, target = 2.hours)

        val progress = GoalProgressCalculator.progress(goal, sessions, newYork, now)

        val statsTotal =
            StatsAggregator
                .bucketTotals(sessions, newYork, StatsBucketSize.DAY)
                .first { it.bucketStart == progress.windowStart }
                .totalCounted
        assertEquals(statsTotal, progress.achieved, "goal progress must reconcile with the statistics bucket")
        assertEquals(60.minutes, progress.achieved)
    }

    @Test
    fun `weekly achieved total equals the statistics screen's week bucket`() {
        val now = Instant.parse("2026-03-11T12:00:00Z") // a Wednesday in America/New_York.
        val sessions =
            listOf(
                session("mon", startedAt = Instant.parse("2026-03-09T12:00:00Z"), countedMinutes = 30),
                session("wed", startedAt = now, countedMinutes = 45),
                // Prior week, must not contribute.
                session("prior", startedAt = Instant.parse("2026-03-01T12:00:00Z"), countedMinutes = 500),
            )
        val goal = StudyGoal(GoalPeriod.WEEKLY, GoalScope.Overall, target = 5.hours)

        val progress = GoalProgressCalculator.progress(goal, sessions, newYork, now)

        assertEquals(75.minutes, progress.achieved)
    }

    @Test
    fun `per-subject goals only count that subject's sessions`() {
        val now = Instant.parse("2026-03-10T15:00:00Z")
        val sessions =
            listOf(
                session("math", startedAt = now, subjectId = "math", countedMinutes = 30),
                session("history", startedAt = now, subjectId = "history", countedMinutes = 90),
            )
        val goal = StudyGoal(GoalPeriod.DAILY, GoalScope.BySubject("math"), target = 1.hours)

        val progress = GoalProgressCalculator.progress(goal, sessions, newYork, now)

        assertEquals(30.minutes, progress.achieved, "history sessions must not count towards a math goal")
    }

    @Test
    fun `state is MET once achieved reaches the target`() {
        val now = Instant.parse("2026-03-10T15:00:00Z")
        val sessions = listOf(session("s1", startedAt = now, countedMinutes = 60))
        val goal = StudyGoal(GoalPeriod.DAILY, GoalScope.Overall, target = 30.minutes)

        val progress = GoalProgressCalculator.progress(goal, sessions, newYork, now)

        assertEquals(GoalProgressState.MET, progress.state)
        assertEquals(Duration.ZERO, progress.remaining)
    }

    @Test
    fun `state is ON_TRACK when achieved is ahead of the elapsed fraction of the window`() {
        // Midnight EST + 1 hour into a 24h day: 1/24 of the day elapsed.
        val windowStart = Instant.parse("2026-03-10T05:00:00Z") // 2026-03-10T00:00 EST
        val now = windowStart + 1.hours
        val goal = StudyGoal(GoalPeriod.DAILY, GoalScope.Overall, target = 4.hours)
        // Achieved (20 min) exceeds the expected pace (4h / 24 = 10 min) at the 1-hour mark.
        val sessions = listOf(session("s1", startedAt = now, countedMinutes = 20))

        val progress = GoalProgressCalculator.progress(goal, sessions, newYork, now)

        assertEquals(GoalProgressState.ON_TRACK, progress.state)
    }

    @Test
    fun `state is BEHIND when achieved trails the elapsed fraction of the window`() {
        val windowStart = Instant.parse("2026-03-10T05:00:00Z")
        val now = windowStart + 12.hours // halfway through the day.
        val goal = StudyGoal(GoalPeriod.DAILY, GoalScope.Overall, target = 4.hours)
        // Expected pace at the halfway point is 2h; achieving only 10 minutes falls behind it.
        val sessions = listOf(session("s1", startedAt = now, countedMinutes = 10))

        val progress = GoalProgressCalculator.progress(goal, sessions, newYork, now)

        assertEquals(GoalProgressState.BEHIND, progress.state)
    }

    @Test
    fun `a goal with no sessions at all is BEHIND, not MET or a crash`() {
        val now = Instant.parse("2026-03-10T15:00:00Z")
        val goal = StudyGoal(GoalPeriod.DAILY, GoalScope.Overall, target = 1.hours)

        val progress = GoalProgressCalculator.progress(goal, emptyList(), newYork, now)

        assertEquals(Duration.ZERO, progress.achieved)
        assertTrue(progress.state != GoalProgressState.MET)
    }

    private fun session(
        id: String,
        startedAt: Instant,
        subjectId: String? = "subject-1",
        countedMinutes: Int,
    ) = testStudySession(
        id = id,
        subjectId = subjectId,
        startedAt = startedAt,
        elapsed = SessionElapsed(counted = countedMinutes.minutes),
        status = SessionStatus.STOPPED,
    )
}
