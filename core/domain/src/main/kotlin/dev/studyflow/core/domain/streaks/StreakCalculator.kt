package dev.studyflow.core.domain.streaks

import dev.studyflow.core.model.StudySession
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Computes a fair, precisely-defined study streak from raw session history (issue #73).
 *
 * ### What counts as a "study day"
 *
 * A local calendar day, in the caller's [TimeZone], where the sum of *counted* (never unverified —
 * see [dev.studyflow.core.model.SessionElapsed]) elapsed time across that day's sessions is at
 * least [MINIMUM_STUDY_DURATION]. The threshold exists only to exclude accidental sub-minute
 * starts/stops from counting as "a day studied"; it is deliberately far below any goal target so a
 * short day never costs the streak on top of already being a short day.
 *
 * ### Timezone-stable day boundaries
 *
 * Sessions are bucketed by converting their [kotlin.time.Instant] `startedAt` to a
 * [kotlinx.datetime.LocalDate] in [TimeZone] first, exactly like
 * [dev.studyflow.core.domain.stats.StatsAggregator] does for statistics buckets — never by dividing
 * an epoch instant by 86,400 seconds, which silently misplaces a day whenever the zone observes
 * DST. Recomputing with a different [TimeZone] (the user having travelled, or the device's system
 * zone having changed) re-derives study days from the same [StudySession.startedAt] instants
 * against the *new* zone, which is the only definition of "timezone travel" that cannot drift.
 *
 * ### Grace days
 *
 * [graceDays] missed days in a row are forgiven: the streak keeps counting through them without
 * resetting, though the forgiven days themselves are not "study days" and do not add to the
 * streak's length. The allowance resets after every study day, so it is a per-gap budget (how
 * forgiving a single lapse can be) rather than a lifetime one — deliberately generous, since the
 * point of a grace day is to stop one missed evening from erasing weeks of real effort, which is
 * the opposite of the "no loss-aversion pressure" goal in issue #73.
 *
 * ### Edited sessions
 *
 * This is a pure function of the current [StudySession] list, recomputed from scratch every call —
 * there is no cached, incremental streak state to go stale. Editing a session's time (see
 * [dev.studyflow.core.domain.session.SessionHistoryEditor]) simply changes which local day it is
 * grouped under the next time [compute] runs.
 *
 * ### "Today" is never a broken day in progress
 *
 * If today has no qualifying session yet, that is not treated as a missed day — the user may still
 * study before it ends. The streak is evaluated as of yesterday until today itself qualifies.
 */
public object StreakCalculator {
    /**
     * The minimum counted time a local day needs to qualify as a study day.
     *
     * Chosen far below any realistic goal target: this exists only to exclude noise (an
     * accidental start/stop), never to require a "good enough" day, so a token amount of studying
     * always keeps the streak alive.
     */
    public val MINIMUM_STUDY_DURATION: Duration = 1.minutes

    public fun compute(
        sessions: List<StudySession>,
        zone: TimeZone,
        now: Instant,
        graceDays: Int = 1,
    ): StreakSummary {
        return compute(
            studyDays = studyDaysOf(sessions, zone),
            today = now.toLocalDateTime(zone).date,
            graceDays = graceDays,
        )
    }

    /**
     * Computes a streak from local days whose study-duration threshold has already been applied.
     *
     * Statistics consumers use this overload because their daily aggregates deliberately do not
     * expose individual sessions. Keeping the run and grace calculations here means those
     * consumers cannot drift from the dashboard's session-based streak.
     */
    public fun compute(
        studyDays: Set<LocalDate>,
        today: LocalDate,
        graceDays: Int = 1,
    ): StreakSummary {
        require(graceDays >= 0) { "graceDays must not be negative, was $graceDays" }
        val asOf = if (today in studyDays) today else today.minus(1, DateTimeUnit.DAY)

        val currentStreak = runLengthEndingAt(asOf, studyDays, graceDays)
        val longestStreak = maxOf(currentStreak, longestRun(studyDays, graceDays))
        val graceDaysUsed = if (currentStreak == 0) 0 else trailingGapLength(asOf, studyDays)

        return StreakSummary(
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            lastStudyDay = studyDays.maxOrNull(),
            graceDaysAllowed = graceDays,
            graceDaysUsedInCurrentRun = graceDaysUsed,
        )
    }

    /** The local calendar days that qualify as study days, per this object's KDoc. */
    private fun studyDaysOf(
        sessions: List<StudySession>,
        zone: TimeZone,
    ): Set<LocalDate> =
        sessions
            .groupBy { it.startedAt.toLocalDateTime(zone).date }
            .filterValues { forDay -> forDay.totalCounted() >= MINIMUM_STUDY_DURATION }
            .keys

    private fun List<StudySession>.totalCounted(): Duration = fold(Duration.ZERO) { acc, s -> acc + s.elapsed.counted }

    /**
     * The length of the run of qualifying days ending at (and including, if it qualifies) [date],
     * tolerating gaps of up to [graceDays] missed days without resetting.
     */
    private fun runLengthEndingAt(
        date: LocalDate,
        studyDays: Set<LocalDate>,
        graceDays: Int,
    ): Int {
        val earliest = studyDays.minOrNull() ?: return 0
        if (date < earliest) return 0

        var current = 0
        var gap = 0
        var cursor = earliest
        while (cursor <= date) {
            if (cursor in studyDays) {
                current++
                gap = 0
            } else {
                gap++
                if (gap > graceDays) current = 0
            }
            cursor = cursor.plus(1, DateTimeUnit.DAY)
        }
        return current
    }

    /** The longest [runLengthEndingAt] ever reaches across the full history of [studyDays]. */
    private fun longestRun(
        studyDays: Set<LocalDate>,
        graceDays: Int,
    ): Int {
        val earliest = studyDays.minOrNull() ?: return 0
        val latest = studyDays.max()

        var longest = 0
        var current = 0
        var gap = 0
        var cursor = earliest
        while (cursor <= latest) {
            if (cursor in studyDays) {
                current++
                gap = 0
                longest = maxOf(longest, current)
            } else {
                gap++
                if (gap > graceDays) current = 0
            }
            cursor = cursor.plus(1, DateTimeUnit.DAY)
        }
        return longest
    }

    /** How many consecutive non-study days immediately precede (and include) [date], if any. */
    private fun trailingGapLength(
        date: LocalDate,
        studyDays: Set<LocalDate>,
    ): Int {
        var used = 0
        var cursor = date
        while (cursor !in studyDays) {
            used++
            cursor = cursor.minus(1, DateTimeUnit.DAY)
        }
        return used
    }
}
