package dev.studyflow.core.domain.stats

import dev.studyflow.core.domain.session.DailySubjectTotal
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A half-open window, `[from, to)`, that statistics are computed over (issue #60).
 *
 * Half-open rather than inclusive on both ends so consecutive ranges (this month, last month) can
 * be built by chaining `to` into the next `from` without either double-counting or dropping the
 * instant they meet at.
 */
public data class StatsRange(
    val from: Instant,
    val to: Instant,
) {
    init {
        require(from < to) { "StatsRange.from ($from) must be before to ($to)" }
    }
}

/** The width of one [BucketTotal] in a trend chart. */
public enum class StatsBucketSize {
    DAY,
    WEEK,
    MONTH,
}

/**
 * Studied time for one bucket of a trend chart.
 *
 * @property bucketStart the bucket's first instant, at local midnight in the zone the bucketing was
 *   done in — never UTC midnight, so a bucket lines up with the calendar day/week/month the user
 *   actually experienced, DST shifts included.
 */
public data class BucketTotal(
    val bucketStart: Instant,
    val totalCounted: Duration,
    val sessionCount: Int,
)

/** Studied time attributed to one subject (or `null` for sessions with no subject) in a range. */
public data class SubjectTotal(
    val subjectId: String?,
    val totalCounted: Duration,
    val sessionCount: Int,
)

/** Valid range of an hour-of-day value, `0..23`. */
private val VALID_HOURS_OF_DAY = 0..23

/** Studied time that started within one local hour-of-day, `0..23`, across a range. */
public data class HourOfDayTotal(
    val hourOfDay: Int,
    val totalCounted: Duration,
    val sessionCount: Int,
) {
    init {
        require(hourOfDay in VALID_HOURS_OF_DAY) { "hourOfDay must be 0..23, was $hourOfDay" }
    }
}

/** The mean length of a stopped session in a range. */
public data class AverageSessionLength(
    val average: Duration,
    val sessionCount: Int,
)

/**
 * Read-only aggregates over session history, for the insights screen (issue #60).
 *
 * Every method here answers a "how much/when" question with a roll-up rather than a row list —
 * [dev.studyflow.core.domain.session.SessionHistoryRepository] already covers "which sessions", so
 * this interface only exists to keep that browsing contract from growing statistics-shaped methods
 * it does not otherwise need. All aggregates share the same filtering rules as the history list:
 * soft-deleted and not-yet-stopped sessions never contribute, so a total here always reconciles
 * with what the history screen shows for the same range.
 */
public interface StatsRepository {
    /** Totals per [bucketSize]-wide bucket covering [range], for a trend chart. */
    public fun observeBucketTotals(
        range: StatsRange,
        bucketSize: StatsBucketSize,
        subjectId: String? = null,
    ): Flow<List<BucketTotal>>

    /** Per-subject totals within [range], for a breakdown chart. */
    public fun observeSubjectBreakdown(
        range: StatsRange,
        subjectId: String? = null,
    ): Flow<List<SubjectTotal>>

    /** One entry per hour of day, `0..23`, within [range], for a time-of-day heatmap. */
    public fun observeHourOfDayHeatmap(
        range: StatsRange,
        subjectId: String? = null,
    ): Flow<List<HourOfDayTotal>>

    /** The mean stopped-session length within [range]. */
    public fun observeAverageSessionLength(
        range: StatsRange,
        subjectId: String? = null,
    ): Flow<AverageSessionLength>

    /** Per-subject, per-local-day totals within [range] — the row shape a CSV export builds from. */
    public fun observeDailySubjectTotals(
        range: StatsRange,
        subjectId: String? = null,
    ): Flow<List<DailySubjectTotal>>
}
