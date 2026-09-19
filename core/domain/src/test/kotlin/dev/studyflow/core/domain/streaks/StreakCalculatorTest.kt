package dev.studyflow.core.domain.streaks

import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.testing.data.testStudySession
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Covers issue #73's acceptance criterion that streak calculation is unit-tested across timezone
 * travel, DST and edited sessions, plus the grace-day rule documented on [StreakCalculator].
 */
@DisplayName("StreakCalculator")
class StreakCalculatorTest {
    private val utc = TimeZone.UTC
    private val newYork = TimeZone.of("America/New_York")

    @Test
    fun `consecutive study days build a streak with no gaps`() {
        val now = Instant.parse("2026-03-05T12:00:00Z")
        val sessions =
            (0..4).map { daysAgo ->
                session("s$daysAgo", startedAt = now - (1.days * daysAgo), countedMinutes = 30)
            }

        val summary = StreakCalculator.compute(sessions, utc, now, graceDays = 0)

        assertEquals(5, summary.currentStreak, "five consecutive study days")
        assertEquals(5, summary.longestStreak)
        assertEquals(LocalDate(2026, 3, 5), summary.lastStudyDay)
        assertEquals(0, summary.graceDaysUsedInCurrentRun)
    }

    @Test
    fun `a gap within the grace-day allowance does not break the streak`() {
        val now = Instant.parse("2026-03-05T12:00:00Z")
        // Studied 2026-03-01, 03-02, then a 1-day gap (03-03 missed), resumed 03-04 and 03-05.
        val sessions =
            listOf(
                session("d1", startedAt = Instant.parse("2026-03-01T12:00:00Z"), countedMinutes = 30),
                session("d2", startedAt = Instant.parse("2026-03-02T12:00:00Z"), countedMinutes = 30),
                session("d4", startedAt = Instant.parse("2026-03-04T12:00:00Z"), countedMinutes = 30),
                session("d5", startedAt = now, countedMinutes = 30),
            )

        val summary = StreakCalculator.compute(sessions, utc, now, graceDays = 1)

        assertEquals(4, summary.currentStreak, "the forgiven gap must not reset the streak")
        assertEquals(0, summary.graceDaysUsedInCurrentRun, "the gap is not trailing today, so no grace is spent now")
    }

    @Test
    fun `a gap beyond the grace-day allowance resets the streak`() {
        val now = Instant.parse("2026-03-10T12:00:00Z")
        // Studied 03-01..03-02, then a 3-day gap (beyond a 1-day allowance), then 03-06..03-10.
        val earlyRun =
            listOf(
                session("e1", startedAt = Instant.parse("2026-03-01T12:00:00Z"), countedMinutes = 30),
                session("e2", startedAt = Instant.parse("2026-03-02T12:00:00Z"), countedMinutes = 30),
            )
        val lateRun =
            (0..4).map { daysAgo ->
                session("l$daysAgo", startedAt = now - (1.days * daysAgo), countedMinutes = 30)
            }

        val summary = StreakCalculator.compute(earlyRun + lateRun, utc, now, graceDays = 1)

        assertEquals(5, summary.currentStreak, "only the run after the excessive gap counts")
        assertEquals(5, summary.longestStreak, "the earlier 2-day run is shorter than the current one")
    }

    @Test
    fun `today without a session yet does not break an otherwise-continuing streak`() {
        val yesterday = Instant.parse("2026-03-04T12:00:00Z")
        val now = Instant.parse("2026-03-05T08:00:00Z") // today, before any session logged.
        val sessions = listOf(session("y", startedAt = yesterday, countedMinutes = 30))

        val summary = StreakCalculator.compute(sessions, utc, now, graceDays = 0)

        assertEquals(1, summary.currentStreak, "yesterday still counts; today in progress is not a miss")
    }

    @Test
    fun `DST spring-forward does not create or destroy a day`() {
        // 2026-03-08 is the US spring-forward date (02:00 -> 03:00 EST->EDT).
        val beforeTransition = Instant.parse("2026-03-07T15:00:00Z") // 2026-03-07T10:00 EST
        val onTransitionDay = Instant.parse("2026-03-08T15:00:00Z") // 2026-03-08T11:00 EDT
        val afterTransition = Instant.parse("2026-03-09T15:00:00Z") // 2026-03-09T11:00 EDT
        val sessions =
            listOf(
                session("s1", startedAt = beforeTransition, countedMinutes = 30),
                session("s2", startedAt = onTransitionDay, countedMinutes = 30),
                session("s3", startedAt = afterTransition, countedMinutes = 30),
            )

        val summary = StreakCalculator.compute(sessions, newYork, afterTransition, graceDays = 0)

        assertEquals(3, summary.currentStreak, "the spring-forward day must still count as exactly one day")
    }

    @Test
    fun `DST fall-back does not create or destroy a day`() {
        // 2026-11-01 is the US fall-back date (02:00 EDT -> 01:00 EST).
        val beforeTransition = Instant.parse("2026-10-31T15:00:00Z")
        val onTransitionDay = Instant.parse("2026-11-01T15:00:00Z")
        val afterTransition = Instant.parse("2026-11-02T15:00:00Z")
        val sessions =
            listOf(
                session("s1", startedAt = beforeTransition, countedMinutes = 30),
                session("s2", startedAt = onTransitionDay, countedMinutes = 30),
                session("s3", startedAt = afterTransition, countedMinutes = 30),
            )

        val summary = StreakCalculator.compute(sessions, newYork, afterTransition, graceDays = 0)

        assertEquals(3, summary.currentStreak, "the fall-back day must still count as exactly one day")
    }

    @Test
    fun `timezone travel re-buckets the same instants under the new zone`() {
        // 2026-01-15T04:30:00Z is 2026-01-14T23:30 in America/New_York (EST, UTC-5) but
        // 2026-01-15T10:00 in Asia/Kolkata (UTC+5:30, no DST) — the same instant lands on two
        // different calendar days depending purely on which zone re-buckets it.
        val kolkata = TimeZone.of("Asia/Kolkata")
        val firstInstant = Instant.parse("2026-01-15T04:30:00Z")
        // 2026-01-15T05:00 EST / 2026-01-15T15:30 IST: a new calendar day in New York, but the
        // same one as firstInstant in Kolkata.
        val secondInstant = Instant.parse("2026-01-15T10:00:00Z")
        val sessions =
            listOf(
                session("s1", startedAt = firstInstant, countedMinutes = 30),
                session("s2", startedAt = secondInstant, countedMinutes = 30),
            )

        val newYorkSummary = StreakCalculator.compute(sessions, newYork, secondInstant, graceDays = 0)
        val kolkataSummary = StreakCalculator.compute(sessions, kolkata, secondInstant, graceDays = 0)

        assertEquals(2, newYorkSummary.currentStreak, "both instants fall on separate New York calendar days")
        assertEquals(1, kolkataSummary.currentStreak, "both instants fall on the same Kolkata calendar day")
    }

    @Test
    fun `editing a session's day changes the recomputed streak`() {
        val now = Instant.parse("2026-03-05T12:00:00Z")
        val original =
            listOf(
                session("d4", startedAt = Instant.parse("2026-03-04T12:00:00Z"), countedMinutes = 30),
                session("d5", startedAt = now, countedMinutes = 30),
            )
        val before = StreakCalculator.compute(original, utc, now, graceDays = 0)

        // Correcting session "d4"'s start time to a non-adjacent day, as SessionHistoryEditor would.
        val edited =
            original.map {
                if (it.id == "d4") it.copy(startedAt = Instant.parse("2026-03-01T12:00:00Z")) else it
            }
        val after = StreakCalculator.compute(edited, utc, now, graceDays = 0)

        assertEquals(2, before.currentStreak, "the original, adjacent-day sessions form a 2-day streak")
        assertEquals(1, after.currentStreak, "moving the session off the adjacent day breaks the streak")
    }

    @Test
    fun `sessions with no counted time below the minimum do not count as a study day`() {
        val now = Instant.parse("2026-03-05T12:00:00Z")
        val sessions = listOf(session("s1", startedAt = now, countedMinutes = 0))

        val summary = StreakCalculator.compute(sessions, utc, now, graceDays = 0)

        assertEquals(0, summary.currentStreak)
        assertNull(summary.lastStudyDay)
    }

    private fun session(
        id: String,
        startedAt: Instant,
        countedMinutes: Int,
    ): StudySession =
        testStudySession(
            id = id,
            startedAt = startedAt,
            elapsed = SessionElapsed(counted = countedMinutes.minutes),
            status = SessionStatus.STOPPED,
        )
}
