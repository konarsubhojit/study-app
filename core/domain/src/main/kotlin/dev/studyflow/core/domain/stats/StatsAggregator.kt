package dev.studyflow.core.domain.stats

import dev.studyflow.core.domain.session.DailySubjectTotal
import dev.studyflow.core.model.StudySession
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The pure roll-up logic shared by every [StatsRepository] implementation (issue #60).
 *
 * Kept independent of Room/any storage so it can be unit-tested on the JVM and reused by a fake
 * repository in tests without re-deriving the same bucketing rules by hand. Callers are expected to
 * have already narrowed [StudySession]s to the target range/subject/stopped-and-not-deleted filter
 * (a `SessionDao` `WHERE` clause in production, a plain list `filter` in a fake) — this object only
 * does the part that is genuinely awkward in SQL: resolving already-computed elapsed time into
 * DST-aware calendar buckets.
 */
public object StatsAggregator {
    private const val HOUR_OF_DAY_MAX = 23

    public fun bucketTotals(
        sessions: List<StudySession>,
        zone: TimeZone,
        bucketSize: StatsBucketSize,
    ): List<BucketTotal> =
        sessions
            .groupBy { it.startedAt.bucketStart(zone, bucketSize) }
            .map { (bucketStart, forBucket) ->
                BucketTotal(
                    bucketStart = bucketStart,
                    totalCounted = forBucket.totalCounted(),
                    sessionCount = forBucket.size,
                )
            }.sortedBy { it.bucketStart }

    public fun subjectBreakdown(sessions: List<StudySession>): List<SubjectTotal> =
        sessions
            .groupBy { it.subjectId }
            .map { (subjectId, forSubject) ->
                SubjectTotal(
                    subjectId = subjectId,
                    totalCounted = forSubject.totalCounted(),
                    sessionCount = forSubject.size,
                )
            }.sortedByDescending { it.totalCounted }

    public fun hourOfDayHeatmap(
        sessions: List<StudySession>,
        zone: TimeZone,
    ): List<HourOfDayTotal> {
        val byHour = sessions.groupBy { it.startedAt.toLocalDateTime(zone).hour }
        return (0..HOUR_OF_DAY_MAX).map { hour ->
            val forHour = byHour[hour].orEmpty()
            HourOfDayTotal(hourOfDay = hour, totalCounted = forHour.totalCounted(), sessionCount = forHour.size)
        }
    }

    public fun averageSessionLength(sessions: List<StudySession>): AverageSessionLength =
        if (sessions.isEmpty()) {
            AverageSessionLength(Duration.ZERO, 0)
        } else {
            AverageSessionLength(sessions.totalCounted() / sessions.size, sessions.size)
        }

    /**
     * Per-subject, per-local-calendar-day totals — the CSV export's row shape (issue #60).
     *
     * A separate cut from [bucketTotals]/[subjectBreakdown] rather than a join of the two: neither
     * of those retains both dimensions at once, and a CSV row genuinely needs "this subject, on
     * this day" rather than either total alone.
     */
    public fun dailySubjectTotals(
        sessions: List<StudySession>,
        zone: TimeZone,
    ): List<DailySubjectTotal> =
        sessions
            .groupBy {
                it.startedAt
                    .toLocalDateTime(zone)
                    .date
                    .toString() to it.subjectId
            }.map { (key, forDay) ->
                val (day, subjectId) = key
                DailySubjectTotal(day = day, subjectId = subjectId, totalCounted = forDay.totalCounted())
            }.sortedWith(compareBy({ it.day }, { it.subjectId ?: "" }))

    private fun List<StudySession>.totalCounted(): Duration =
        fold(Duration.ZERO) { acc, session -> acc + session.elapsed.counted }

    /**
     * The local instant a [StatsBucketSize] bucket containing [this] starts at.
     *
     * Converts to [LocalDate] in [zone] first and truncates *that*, rather than doing arithmetic on
     * the [Instant] directly: a day is not always 86,400 seconds and a week is not always seven of
     * those seconds exactly once DST is involved, but the calendar concepts of "this day", "this
     * week" and "this month" are unaffected by the clock shifting underneath them.
     *
     * Visible to [dev.studyflow.core.domain.goals.GoalProgressCalculator] (issue #73) so a goal's
     * "today"/"this week" window is resolved with the exact same bucketing rule the statistics
     * screen uses, rather than a second implementation that could quietly drift from this one.
     */
    internal fun Instant.bucketStart(
        zone: TimeZone,
        bucketSize: StatsBucketSize,
    ): Instant {
        val date = toLocalDateTime(zone).date
        val truncated =
            when (bucketSize) {
                StatsBucketSize.DAY -> date

                // DayOfWeek.ordinal is 0 for MONDAY..6 for SUNDAY, so subtracting it walks back to
                // that week's Monday — an ISO week, matching the history screen's day grouping.
                StatsBucketSize.WEEK -> date.minus(date.dayOfWeek.ordinal, DateTimeUnit.DAY)

                StatsBucketSize.MONTH -> LocalDate(date.year, date.month, 1)
            }
        return truncated.atStartOfDayIn(zone)
    }
}
