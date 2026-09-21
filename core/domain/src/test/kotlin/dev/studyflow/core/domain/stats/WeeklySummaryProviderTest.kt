package dev.studyflow.core.domain.stats

import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.testing.data.FakeStatsRepository
import dev.studyflow.core.testing.data.testStudySession
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The recap is built from the insights screen's own aggregates, so these tests drive it through
 * [FakeStatsRepository] — the same [StatsAggregator] roll-ups production uses (issue #63).
 */
@DisplayName("WeeklySummaryProvider")
class WeeklySummaryProviderTest {
    private val zone = TimeZone.of("America/New_York")
    private val timeZoneProvider = TimeZoneProvider { zone }

    /** Sunday 2026-03-08, 20:00 local (New York is UTC-4 by then). */
    private val sundayEvening = Instant.parse("2026-03-09T00:00:00Z")

    @Test
    fun `totals only the seven local days ending today`() =
        runTest {
            val summary =
                summaryOf(
                    session("in-window", "2026-03-02T15:00:00Z", 60),
                    session("also-in-window", "2026-03-08T15:00:00Z", 30),
                    session("week-before", "2026-03-01T15:00:00Z", 120),
                )

            assertEquals(LocalDate(2026, 3, 2), summary.window.start)
            assertEquals(LocalDate(2026, 3, 8), summary.window.endInclusive)
            assertEquals(90.minutes, summary.totalCounted)
            assertEquals(120.minutes, summary.previousWeekCounted)
            assertEquals((-30).minutes, summary.weekOverWeekDelta)
        }

    @Test
    fun `top subjects are the biggest three, largest first`() =
        runTest {
            val summary =
                summaryOf(
                    session("a", "2026-03-03T15:00:00Z", 30, subjectId = "history"),
                    session("b", "2026-03-04T15:00:00Z", 90, subjectId = "maths"),
                    session("c", "2026-03-05T15:00:00Z", 45, subjectId = "physics"),
                    session("d", "2026-03-06T15:00:00Z", 10, subjectId = "art"),
                )

            assertEquals(
                listOf("maths", "physics", "history"),
                summary.topSubjects.map { it.subjectId },
                "a recap names the three that mattered, in order",
            )
        }

    @Test
    fun `streak uses the dashboard grace-day policy and survives a quiet today`() =
        runTest {
            val summary =
                summaryOf(
                    session("d1", "2026-03-06T15:00:00Z", 30),
                    session("d2", "2026-03-07T15:00:00Z", 30),
                    // Nothing on Sunday the 8th — the day is not over, so the streak still stands.
                    session("gap-before", "2026-03-04T15:00:00Z", 30),
                )

            assertEquals(3, summary.streakDays)
        }

    @Test
    fun `streak ignores daily totals below the dashboard qualification threshold`() =
        runTest {
            val sunday = Instant.parse("2026-03-08T15:00:00Z")
            val summary =
                summaryOf(
                    session("yesterday", "2026-03-07T15:00:00Z", 30),
                    testStudySession(
                        id = "accidental-start",
                        subjectId = "maths",
                        startedAt = sunday,
                        endedAt = sunday + 30.seconds,
                        status = SessionStatus.STOPPED,
                        elapsed = SessionElapsed(counted = 30.seconds),
                    ),
                )

            assertEquals(1, summary.streakDays)
        }

    @Test
    fun `goal attainment is measured against the weekly goal`() =
        runTest {
            val summary = summaryOf(session("s", "2026-03-04T15:00:00Z", 7 * 60 / 2))

            assertEquals(StudyGoals.DEFAULT_WEEKLY_FOCUS_GOAL, summary.weeklyGoal)
            assertEquals(50, summary.goalAttainmentPercent)
            assertTrue(summary.hasActivity)
        }

    @Test
    fun `a week without sessions has no activity to report`() =
        runTest {
            val summary = summaryOf(session("last-week", "2026-02-28T15:00:00Z", 60))

            assertEquals(Duration.ZERO, summary.totalCounted)
            assertFalse(summary.hasActivity, "an empty week is skipped rather than announced as zero")
            assertEquals(0, summary.goalAttainmentPercent)
        }

    @Test
    fun `summary copy names every figure the statistics screen shows`() =
        runTest {
            val summary =
                summaryOf(
                    session("a", "2026-03-04T15:00:00Z", 130, subjectId = "maths"),
                    session("b", "2026-03-05T15:00:00Z", 20, subjectId = "history"),
                )

            val text = WeeklySummaryCopy.shareText(summary) { id -> id ?: "No subject" }

            assertTrue(text.contains("2 h 30 min studied"), text)
            assertTrue(text.contains("2026-03-02 to 2026-03-08"), text)
            assertTrue(text.contains("maths 2 h 10 min"), text)
            assertTrue(text.contains("No current streak"), text)
            assertTrue(text.contains("35% of your 7 h 0 min weekly goal"), text)
            assertTrue(text.contains("Up 2 h 30 min on the week before"), text)
        }

    private suspend fun summaryOf(vararg sessions: StudySession): WeeklySummary =
        WeeklySummaryProvider(
            statsRepository = FakeStatsRepository(timeZoneProvider, sessions.toList()),
            timeZoneProvider = timeZoneProvider,
        ).summaryAt(sundayEvening)

    private fun session(
        id: String,
        startedAt: String,
        countedMinutes: Int,
        subjectId: String? = "maths",
    ): StudySession =
        testStudySession(
            id = id,
            subjectId = subjectId,
            startedAt = Instant.parse(startedAt),
            endedAt = Instant.parse(startedAt) + countedMinutes.minutes,
            status = SessionStatus.STOPPED,
            elapsed = SessionElapsed(counted = countedMinutes.minutes),
        )

    @Test
    fun `an hour-long duration reads as hours and minutes`() {
        assertEquals("1 h 5 min", WeeklySummaryCopy.durationLabel(65.minutes))
        assertEquals("45 min", WeeklySummaryCopy.durationLabel(45.minutes))
        assertEquals("2 h 0 min", WeeklySummaryCopy.durationLabel(2.hours))
    }
}
