package dev.studyflow.core.domain.goals

import dev.studyflow.core.domain.stats.StatsAggregator
import dev.studyflow.core.domain.stats.StatsAggregator.bucketStart
import dev.studyflow.core.domain.stats.StatsBucketSize
import dev.studyflow.core.model.StudySession
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Computes [GoalProgress] for a [StudyGoal] against a [now] instant (issue #73).
 *
 * Deliberately reuses [StatsAggregator.bucketTotals] rather than re-deriving a window total by
 * hand: that is the exact roll-up `feature/insights` renders on the statistics screen, so a goal's
 * "achieved so far" is guaranteed to reconcile with what that screen shows for the same range,
 * satisfying issue #73's "goal progress matches the statistics screen exactly" acceptance
 * criterion by construction rather than by a second implementation staying in sync by convention.
 *
 * Like [StatsAggregator], this does no filtering of its own: callers are expected to have already
 * narrowed [StudySession]s to stopped, non-deleted sessions, the same as any other stats
 * consumption.
 */
public object GoalProgressCalculator {
    /** Days added to a window's start to find its (exclusive) end, per [GoalPeriod]. */
    private const val DAILY_WINDOW_DAYS = 1
    private const val WEEKLY_WINDOW_DAYS = 7

    public fun progress(
        goal: StudyGoal,
        sessions: List<StudySession>,
        zone: TimeZone,
        now: Instant,
    ): GoalProgress {
        val bucketSize = goal.period.toBucketSize()
        val scoped = sessions.filterByScope(goal.scope)
        val windowStart = now.bucketStart(zone, bucketSize)
        val windowEnd = windowStart.windowEnd(zone, goal.period)
        val achieved =
            StatsAggregator
                .bucketTotals(scoped, zone, bucketSize)
                .firstOrNull { it.bucketStart == windowStart }
                ?.totalCounted
                ?: Duration.ZERO
        return GoalProgress(
            goal = goal,
            achieved = achieved,
            windowStart = windowStart,
            windowEnd = windowEnd,
            state = state(goal.target, achieved, windowStart, windowEnd, now),
        )
    }

    private fun List<StudySession>.filterByScope(scope: GoalScope): List<StudySession> =
        when (scope) {
            GoalScope.Overall -> this
            is GoalScope.BySubject -> filter { it.subjectId == scope.subjectId }
        }

    private fun GoalPeriod.toBucketSize(): StatsBucketSize =
        when (this) {
            GoalPeriod.DAILY -> StatsBucketSize.DAY
            GoalPeriod.WEEKLY -> StatsBucketSize.WEEK
        }

    /** The window's exclusive end: [DAILY_WINDOW_DAYS]/[WEEKLY_WINDOW_DAYS] days after its start. */
    private fun Instant.windowEnd(
        zone: TimeZone,
        period: GoalPeriod,
    ): Instant {
        val days =
            when (period) {
                GoalPeriod.DAILY -> DAILY_WINDOW_DAYS
                GoalPeriod.WEEKLY -> WEEKLY_WINDOW_DAYS
            }
        return toLocalDateTime(zone).date.plus(days, DateTimeUnit.DAY).atStartOfDayIn(zone)
    }

    /**
     * A pace check, not a countdown: on track means "at or ahead of the fraction of the target
     * that steady effort across the whole window would already have reached by now", never a
     * loss-aversion message about what has been missed (issue #73's "no dark patterns").
     */
    private fun state(
        target: Duration,
        achieved: Duration,
        windowStart: Instant,
        windowEnd: Instant,
        now: Instant,
    ): GoalProgressState {
        if (achieved >= target) return GoalProgressState.MET
        val windowLength = windowEnd - windowStart
        val elapsed = (now - windowStart).coerceIn(Duration.ZERO, windowLength)
        val expectedSoFar = target * (elapsed / windowLength)
        return if (achieved >= expectedSoFar) GoalProgressState.ON_TRACK else GoalProgressState.BEHIND
    }
}
