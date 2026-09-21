package dev.studyflow.core.domain.stats

import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.domain.streaks.StreakCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/** How far back the streak count looks; a longer streak than this is reported as this many days. */
private const val STREAK_LOOKBACK_DAYS = 365

/** How many subjects a recap names before it stops being a recap. */
private const val TOP_SUBJECT_COUNT = 3

/**
 * Builds the weekly recap out of the insights screen's own aggregates (issue #63).
 *
 * Reads [StatsRepository] rather than sessions directly so the digest cannot drift from the
 * statistics screen: both go through the same range filtering and the same [StatsAggregator]
 * roll-ups, and a change to what counts as studied time lands in both at once.
 *
 * One daily-bucket query covers both weeks *and* the streak lookback, so the summary is two
 * queries rather than four, and the current week's total is read from the same subject breakdown
 * the recap lists — a total that could disagree with its own breakdown would be worse than no
 * total at all.
 */
public class WeeklySummaryProvider(
    private val statsRepository: StatsRepository,
    private val timeZoneProvider: TimeZoneProvider,
    private val weeklyGoal: Duration = StudyGoals.DEFAULT_WEEKLY_FOCUS_GOAL,
) {
    public fun observe(now: Instant): Flow<WeeklySummary> {
        val zone = timeZoneProvider.current()
        val window = WeeklySummaryWindow.endingAt(now, zone)
        val lookback =
            StatsRange(
                from = window.endExclusive.minus(STREAK_LOOKBACK_DAYS, DateTimeUnit.DAY).atStartOfDayIn(zone),
                to = window.range.to,
            )
        return combine(
            statsRepository.observeBucketTotals(lookback, StatsBucketSize.DAY),
            statsRepository.observeSubjectBreakdown(window.range),
        ) { dailyTotals, subjectTotals ->
            val previousRange = window.previous.range
            WeeklySummary(
                window = window,
                totalCounted = subjectTotals.fold(Duration.ZERO) { total, bucket -> total + bucket.totalCounted },
                previousWeekCounted =
                    dailyTotals
                        .filter { it.bucketStart >= previousRange.from && it.bucketStart < previousRange.to }
                        .fold(Duration.ZERO) { total, bucket -> total + bucket.totalCounted },
                topSubjects = subjectTotals.filter { it.totalCounted > Duration.ZERO }.take(TOP_SUBJECT_COUNT),
                streakDays =
                    StreakCalculator
                        .compute(
                            studyDays =
                                dailyTotals
                                    .filter { it.totalCounted >= StreakCalculator.MINIMUM_STUDY_DURATION }
                                    .map { it.bucketStart.toLocalDateTime(zone).date }
                                    .toSet(),
                            today = now.toLocalDateTime(zone).date,
                        ).currentStreak,
                weeklyGoal = weeklyGoal,
            )
        }
    }

    /** A one-shot read, for the background worker that has nothing to keep observing. */
    public suspend fun summaryAt(now: Instant): WeeklySummary = observe(now).first()
}
