package dev.studyflow.core.domain.stats

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** How many local days one weekly summary covers. */
private const val DAYS_PER_WEEK = 7

/** The goals a summary measures attainment against (issue #63). */
public object StudyGoals {
    /** The dashboard's daily focus target. */
    public val DEFAULT_DAILY_FOCUS_GOAL: Duration = 60.minutes

    /** A week of [DEFAULT_DAILY_FOCUS_GOAL] days — what a weekly recap reports attainment against. */
    public val DEFAULT_WEEKLY_FOCUS_GOAL: Duration = DEFAULT_DAILY_FOCUS_GOAL * DAYS_PER_WEEK
}

/**
 * The seven local days a weekly summary reports on, plus the arithmetic for the week before it.
 *
 * Expressed as [LocalDate]s converted to instants on demand rather than as a raw [StatsRange]: a
 * week is seven *calendar* days in the user's zone, which is not always `7 * 24` hours once a DST
 * shift lands inside it, and the notification's "Mon 2 Mar – Sun 8 Mar" wording needs the dates
 * anyway.
 */
public data class WeeklySummaryWindow(
    val start: LocalDate,
    val endExclusive: LocalDate,
    val zone: TimeZone,
) {
    /** The last day the summary covers, for display — [endExclusive] is one day past it. */
    val endInclusive: LocalDate get() = endExclusive.minus(1, DateTimeUnit.DAY)

    /** The half-open instant range the statistics queries run over. */
    val range: StatsRange
        get() = StatsRange(from = start.atStartOfDayIn(zone), to = endExclusive.atStartOfDayIn(zone))

    /** The seven days immediately before this window — the baseline a week-over-week delta uses. */
    val previous: WeeklySummaryWindow
        get() = WeeklySummaryWindow(start.minus(DAYS_PER_WEEK, DateTimeUnit.DAY), start, zone)

    public companion object {
        /** The week of seven days ending on (and including) [day]. */
        public fun endingOn(
            day: LocalDate,
            zone: TimeZone,
        ): WeeklySummaryWindow =
            WeeklySummaryWindow(
                start = day.minus(DAYS_PER_WEEK - 1, DateTimeUnit.DAY),
                endExclusive = day.plus(1, DateTimeUnit.DAY),
                zone = zone,
            )

        /** The week ending on the local day [now] falls in. */
        public fun endingAt(
            now: Instant,
            zone: TimeZone,
        ): WeeklySummaryWindow = endingOn(now.toLocalDateTime(zone).date, zone)
    }
}

/**
 * A week's recap: what the notification says and what the summary screen renders (issue #63).
 *
 * Every figure is derived from the same [StatsRepository] aggregates the insights screen reads, so
 * "content matches the statistics screen exactly" is a property of where the numbers come from
 * rather than something two code paths have to agree on by hand.
 */
public data class WeeklySummary(
    val window: WeeklySummaryWindow,
    val totalCounted: Duration,
    val previousWeekCounted: Duration,
    val topSubjects: List<SubjectTotal>,
    val streakDays: Int,
    val weeklyGoal: Duration = StudyGoals.DEFAULT_WEEKLY_FOCUS_GOAL,
) {
    /**
     * Whether the week is worth telling the user about at all.
     *
     * A week with no studied time is skipped entirely rather than delivered as "you studied 0
     * hours", which is the one thing the digest must never say (issue #63).
     */
    val hasActivity: Boolean get() = totalCounted > Duration.ZERO

    /** Positive when this week beat the one before it, negative when it fell short. */
    val weekOverWeekDelta: Duration get() = totalCounted - previousWeekCounted

    /** Progress towards [weeklyGoal] as a percentage, uncapped so beating the goal still shows. */
    val goalAttainmentPercent: Int
        get() =
            if (weeklyGoal <= Duration.ZERO) {
                0
            } else {
                ((totalCounted / weeklyGoal) * PERCENT).toInt()
            }

    private companion object {
        const val PERCENT = 100
    }
}

/** How many consecutive days of studying ended on (or just before) a given day. */
public object StudyStreak {
    /**
     * Counts back from [today] over [studiedDays].
     *
     * Today not being in [studiedDays] does not break a streak — the day is not over yet — so the
     * count restarts from yesterday; a gap before that ends it.
     */
    public fun currentStreak(
        studiedDays: Set<LocalDate>,
        today: LocalDate,
    ): Int {
        var day = if (today in studiedDays) today else today.minus(1, DateTimeUnit.DAY)
        var streak = 0
        while (day in studiedDays) {
            streak++
            day = day.minus(1, DateTimeUnit.DAY)
        }
        return streak
    }
}
