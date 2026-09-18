package dev.studyflow.core.domain.stats

import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.testing.data.testStudySession
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The roll-up logic is exercised directly here — no Room, no SQL — because it is the part every
 * [StatsRepository] implementation shares, and fixture-independent tests are what keep the SQL
 * query and the Kotlin bucketing honest against each other (issue #60).
 */
@DisplayName("StatsAggregator")
class StatsAggregatorTest {
    private val newYork = TimeZone.of("America/New_York")

    @Test
    fun `sums counted time per subject, ignoring unverified time`() {
        val sessions =
            listOf(
                session("s1", subjectId = "math", countedMinutes = 30, unverifiedMinutes = 10),
                session("s2", subjectId = "math", countedMinutes = 15),
                session("s3", subjectId = "history", countedMinutes = 20),
            )

        val breakdown = StatsAggregator.subjectBreakdown(sessions)

        assertEquals(2, breakdown.size, "one entry per subject")
        val math = breakdown.first { it.subjectId == "math" }
        assertEquals(45.minutes, math.totalCounted, "unverified time must never be counted")
        assertEquals(2, math.sessionCount)
    }

    @Test
    fun `manually overridden sessions contribute their override total, unchanged`() {
        val overridden =
            testStudySession(
                id = "s-override",
                subjectId = "math",
                elapsed = SessionElapsed(counted = 90.minutes, unverified = Duration.ZERO),
                status = SessionStatus.STOPPED,
            )

        val breakdown = StatsAggregator.subjectBreakdown(listOf(overridden))

        assertEquals(90.minutes, breakdown.single().totalCounted, "override total must reconcile exactly")
    }

    @Test
    fun `average session length is the mean of counted elapsed`() {
        val sessions =
            listOf(
                session("s1", countedMinutes = 10),
                session("s2", countedMinutes = 20),
                session("s3", countedMinutes = 30),
            )

        val average = StatsAggregator.averageSessionLength(sessions)

        assertEquals(20.minutes, average.average)
        assertEquals(3, average.sessionCount)
    }

    @Test
    fun `average of no sessions is zero rather than a division by zero`() {
        val average = StatsAggregator.averageSessionLength(emptyList())

        assertEquals(Duration.ZERO, average.average)
        assertEquals(0, average.sessionCount)
    }

    @Test
    fun `hour-of-day buckets every local hour, even ones with no sessions`() {
        val sessions = listOf(session("s1", startedAt = Instant.parse("2026-03-01T14:30:00Z"), countedMinutes = 5))

        val heatmap = StatsAggregator.hourOfDayHeatmap(sessions, TimeZone.UTC)

        assertEquals(24, heatmap.size, "every hour of the day must be represented")
        assertEquals(5.minutes, heatmap.single { it.hourOfDay == 14 }.totalCounted)
        assertEquals(1, heatmap.count { it.totalCounted > Duration.ZERO }, "only the studied hour has time")
    }

    @Test
    fun `daily buckets split at local midnight, not UTC midnight`() {
        // 2026-03-01T04:30:00Z is 2026-02-28T23:30 EST; 2026-03-01T05:30:00Z is 2026-03-01T00:30 EST.
        val lateFeb = session("s1", startedAt = Instant.parse("2026-03-01T04:30:00Z"), countedMinutes = 10)
        val earlyMar = session("s2", startedAt = Instant.parse("2026-03-01T05:30:00Z"), countedMinutes = 20)

        val buckets = StatsAggregator.bucketTotals(listOf(lateFeb, earlyMar), newYork, StatsBucketSize.DAY)

        assertEquals(2, buckets.size, "the two sessions fall on different local calendar days")
    }

    @Test
    fun `weekly buckets start on the local Monday`() {
        // 2026-03-02 is a Monday.
        val monday = session("s1", startedAt = Instant.parse("2026-03-02T12:00:00Z"), countedMinutes = 10)
        val wednesday = session("s2", startedAt = Instant.parse("2026-03-04T12:00:00Z"), countedMinutes = 20)

        val buckets = StatsAggregator.bucketTotals(listOf(monday, wednesday), TimeZone.UTC, StatsBucketSize.WEEK)

        assertEquals(1, buckets.size, "both sessions fall in the same ISO week")
        assertEquals(30.minutes, buckets.single().totalCounted)
        assertEquals(Instant.parse("2026-03-02T00:00:00Z"), buckets.single().bucketStart)
    }

    @Test
    fun `monthly buckets start on the first of the local month`() {
        val early = session("s1", startedAt = Instant.parse("2026-03-01T12:00:00Z"), countedMinutes = 10)
        val late = session("s2", startedAt = Instant.parse("2026-03-30T12:00:00Z"), countedMinutes = 20)

        val buckets = StatsAggregator.bucketTotals(listOf(early, late), TimeZone.UTC, StatsBucketSize.MONTH)

        assertEquals(1, buckets.size)
        assertEquals(Instant.parse("2026-03-01T00:00:00Z"), buckets.single().bucketStart)
    }

    private fun session(
        id: String,
        subjectId: String? = "subject-1",
        startedAt: Instant = Instant.parse("2026-03-01T09:00:00Z"),
        countedMinutes: Int = 0,
        unverifiedMinutes: Int = 0,
    ) = testStudySession(
        id = id,
        subjectId = subjectId,
        startedAt = startedAt,
        endedAt = startedAt + countedMinutes.minutes,
        status = SessionStatus.STOPPED,
        elapsed = SessionElapsed(counted = countedMinutes.minutes, unverified = unverifiedMinutes.minutes),
    )
}
