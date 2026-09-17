package dev.studyflow.core.domain.reminder

import dev.studyflow.core.model.RecurrenceEnd
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.RecurrenceRule
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/**
 * Expands the [RecurrenceRule] subset into concrete occurrences.
 *
 * ### Why only the next one is ever materialised
 *
 * The tempting implementation pre-computes a year of rows and schedules them all. It breaks the
 * first time the user edits the rule, flies to another timezone, or the clocks change — every
 * pre-computed row is now an hour wrong and there is no single place to fix it. Deriving the next
 * occurrence on demand means the rule is the only stored truth and any change takes effect
 * immediately.
 *
 * ### Why the arithmetic happens in local time
 *
 * "Every day at 08:00" is a statement about the user's wall clock, not about a fixed number of
 * seconds. Adding 24 hours to an instant drifts by an hour across a DST boundary. Adding one *day*
 * to a [LocalDate] and only then resolving to an instant keeps 08:00 at 08:00 all year.
 */
public object RecurrenceCalculator {
    /**
     * The first occurrence strictly after [after].
     *
     * @param rule the repeat rule; `null` means the reminder fires once, at [start].
     * @param start the first occurrence, in local wall-clock time.
     * @param zone the zone [start] is expressed in.
     * @param after occurrences at or before this instant are skipped.
     * @return the next occurrence, or `null` when the rule has run out of occurrences.
     */
    public fun nextOccurrence(
        rule: RecurrenceRule?,
        start: LocalDateTime,
        zone: TimeZone,
        after: Instant,
    ): Instant? =
        occurrences(rule, start, zone)
            .dropWhile { it <= after }
            .firstOrNull()

    /**
     * All occurrences the rule will ever produce, lazily.
     *
     * Bounded rules ([RecurrenceEnd.AfterOccurrences], [RecurrenceEnd.OnDate]) yield a finite
     * sequence; [RecurrenceEnd.Never] yields an infinite one, so callers must not collect it
     * eagerly.
     */
    public fun occurrences(
        rule: RecurrenceRule?,
        start: LocalDateTime,
        zone: TimeZone,
    ): Sequence<Instant> =
        // Resolving to an instant last is what makes the whole thing DST-safe: the local time of
        // day is preserved, and kotlinx-datetime applies the zone's rules for that specific date.
        localOccurrences(rule, start).map { it.toInstant(zone) }

    /**
     * The same occurrences as [occurrences], left in wall-clock time.
     *
     * The series is advanced in local time rather than by round-tripping an instant: on the day a
     * zone springs forward, a 01:30 occurrence has no instant of its own, and re-reading the
     * resolved instant as a local time would move the whole rest of the series to 02:30.
     */
    public fun localOccurrences(
        rule: RecurrenceRule?,
        start: LocalDateTime,
    ): Sequence<LocalDateTime> {
        if (rule == null) return sequenceOf(start)
        // Exceptions are removed *after* the rule has been bounded, exactly as iCalendar's EXDATE
        // is: an occurrence the user deleted still counts against "ten times", otherwise deleting
        // one silently extends the series by a week.
        return boundedDates(rule, start.date)
            .filterNot { it in rule.exceptions }
            .map { LocalDateTime(it, start.time) }
    }

    /**
     * Moves a series on by exactly one occurrence.
     *
     * Advancing is a rule rewrite rather than a date change, because the two halves have to stay
     * consistent: a "ten times" rule that keeps its count while its start moves forward would fire
     * ten more times from the new start, and exceptions that can no longer be reached would
     * accumulate on the rule forever.
     *
     * Exactly one occurrence is consumed per call, whenever the call happens. A user who ticks
     * Monday's task off on Wednesday moves the series to Tuesday, not to Thursday: skipping ahead
     * to "now" would silently drop the occurrences in between.
     *
     * @param start the occurrence currently materialised — the head of the series.
     * @return the next head and the rule that describes the series from there, or `null` when this
     *   was the last occurrence.
     */
    public fun advance(
        rule: RecurrenceRule?,
        start: LocalDateTime,
    ): SeriesAdvance? {
        if (rule == null) return null

        val anchored = rule.anchoredTo(start.date)
        val dates = boundedDates(anchored, start.date)
        // Everything the rule generates before the next live occurrence: the head itself, and any
        // date the user has removed. Counting them is what keeps "ten times" honest — the budget is
        // spent by what the rule produced, not by what survived.
        val spent = dates.takeWhile { it <= start.date || it in anchored.exceptions }.count()
        val nextDate = dates.drop(spent).firstOrNull() ?: return null

        val remaining =
            when (val end = anchored.end) {
                is RecurrenceEnd.AfterOccurrences -> RecurrenceEnd.AfterOccurrences(end.count - spent)
                else -> end
            }
        return SeriesAdvance(
            start = LocalDateTime(nextDate, start.time),
            rule =
                anchored.copy(
                    end = remaining,
                    exceptions = anchored.exceptions.filterTo(mutableSetOf()) { it > nextDate },
                ),
        )
    }

    /**
     * Writes down whatever the rule was leaving to its start date.
     *
     * A rule such as "monthly" means "monthly on the 31st" only for as long as its start says so.
     * Move the start to the 28th of February — which is where the 31st lands in a short month — and
     * the unstated day quietly becomes the 28th for the rest of time. Pinning the implied day,
     * month and weekday to the *current* head before it moves keeps the series the user created.
     */
    private fun RecurrenceRule.anchoredTo(start: LocalDate): RecurrenceRule =
        when (frequency) {
            RecurrenceFrequency.DAILY -> {
                this
            }

            RecurrenceFrequency.WEEKLY -> {
                if (daysOfWeek.isEmpty()) copy(daysOfWeek = setOf(start.dayOfWeek)) else this
            }

            RecurrenceFrequency.MONTHLY -> {
                withPinnedDay(start)
            }

            RecurrenceFrequency.YEARLY -> {
                withPinnedDay(start).let {
                    if (monthOfYear == null) it.copy(monthOfYear = start.month.ordinal + 1) else it
                }
            }
        }

    private fun RecurrenceRule.withPinnedDay(start: LocalDate): RecurrenceRule =
        if (weekOfMonth == null && dayOfMonth == null) copy(dayOfMonth = start.day) else this

    /** The occurrence dates of [rule], with its end applied but its exceptions still present. */
    private fun boundedDates(
        rule: RecurrenceRule,
        start: LocalDate,
    ): Sequence<LocalDate> {
        val dates =
            when (rule.frequency) {
                RecurrenceFrequency.DAILY -> dailyDates(start, rule.interval)
                RecurrenceFrequency.WEEKLY -> weeklyDates(start, rule.interval, rule)
                RecurrenceFrequency.MONTHLY -> monthlyDates(start, rule)
                RecurrenceFrequency.YEARLY -> yearlyDates(start, rule)
            }
        return when (val end = rule.end) {
            RecurrenceEnd.Never -> dates
            is RecurrenceEnd.AfterOccurrences -> dates.take(end.count)
            is RecurrenceEnd.OnDate -> dates.takeWhile { it <= end.date }
        }
    }

    private fun dailyDates(
        start: LocalDate,
        interval: Int,
    ): Sequence<LocalDate> = generateSequence(start) { it.plus(interval, DateTimeUnit.DAY) }

    private fun weeklyDates(
        start: LocalDate,
        interval: Int,
        rule: RecurrenceRule,
    ): Sequence<LocalDate> {
        val selected =
            rule.daysOfWeek
                .ifEmpty { setOf(start.dayOfWeek) }
                .map { it.isoDayNumber }
                .sorted()
        // Anchor to the Monday of the start week so "every other Tuesday and Thursday" stays in
        // phase regardless of which of those days the user happened to create the reminder on.
        val anchor = start.minus(start.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
        return generateSequence(anchor) { it.plus(interval * DAYS_PER_WEEK, DateTimeUnit.DAY) }
            .flatMap { weekStart -> selected.asSequence().map { weekStart.plus(it - 1, DateTimeUnit.DAY) } }
            .dropWhile { it < start }
    }

    private fun monthlyDates(
        start: LocalDate,
        rule: RecurrenceRule,
    ): Sequence<LocalDate> =
        monthStarts(LocalDate(start.year, start.month, 1), rule.interval)
            .pickDayOfMonth(rule, start)
            .dropWhile { it < start }

    private fun yearlyDates(
        start: LocalDate,
        rule: RecurrenceRule,
    ): Sequence<LocalDate> {
        // The anchor is the named month of the start year, even when that month is already past:
        // `dropWhile` moves to the first occurrence at or after the start, so "every year in
        // January" created in June first fires next January rather than this one.
        val anchor = rule.monthOfYear?.let { LocalDate(start.year, it, 1) } ?: LocalDate(start.year, start.month, 1)
        return monthStarts(anchor, rule.interval * MONTHS_PER_YEAR)
            .pickDayOfMonth(rule, start)
            .dropWhile { it < start }
    }

    private fun monthStarts(
        anchor: LocalDate,
        stepMonths: Int,
    ): Sequence<LocalDate> = generateSequence(anchor) { it.plus(stepMonths, DateTimeUnit.MONTH) }

    /**
     * Resolves each month of the sequence to the day the rule picks out.
     *
     * A rule counts a weekday ("the 2nd Tuesday") or names a date ("the 31st"), never both, and the
     * two miss a month in different ways: there is no 5th Tuesday in most months, so that month has
     * no occurrence at all, whereas the 31st clamps to the end of a short month.
     */
    private fun Sequence<LocalDate>.pickDayOfMonth(
        rule: RecurrenceRule,
        start: LocalDate,
    ): Sequence<LocalDate> {
        val weekOfMonth = rule.weekOfMonth
        return if (weekOfMonth == null) {
            val day = rule.dayOfMonth ?: start.day
            map { it.withDayClampedToMonth(day) }
        } else {
            val weekday = rule.daysOfWeek.first()
            mapNotNull { it.nthWeekdayOfMonth(weekday, weekOfMonth) }
        }
    }

    /**
     * The [nth] [weekday] of this date's month, or `null` when the month has no such weekday.
     *
     * [RecurrenceRule.LAST_WEEK_OF_MONTH] counts back from the end, which is the only way to say
     * "the last Friday of the month" without the answer changing between a four- and five-Friday
     * month.
     */
    private fun LocalDate.nthWeekdayOfMonth(
        weekday: DayOfWeek,
        nth: Int,
    ): LocalDate? {
        val firstOfMonth = LocalDate(year, month, 1)
        val shift = (weekday.isoDayNumber - firstOfMonth.dayOfWeek.isoDayNumber + DAYS_PER_WEEK) % DAYS_PER_WEEK
        val first = firstOfMonth.plus(shift, DateTimeUnit.DAY)
        val lastDay = firstOfMonth.lastDayOfMonth()
        return if (nth == RecurrenceRule.LAST_WEEK_OF_MONTH) {
            val weeks = (lastDay - first.day) / DAYS_PER_WEEK
            first.plus(weeks * DAYS_PER_WEEK, DateTimeUnit.DAY)
        } else {
            first.plus((nth - 1) * DAYS_PER_WEEK, DateTimeUnit.DAY).takeIf { it.month == month }
        }
    }

    /**
     * Clamps a day-of-month to the length of the month.
     *
     * "The 31st of every month" has to mean *something* in February. Clamping to the 28th/29th
     * keeps the reminder monthly; skipping the short months quietly drops a third of them.
     */
    private fun LocalDate.withDayClampedToMonth(day: Int): LocalDate =
        LocalDate(year, month, minOf(day, LocalDate(year, month, 1).lastDayOfMonth()))

    private fun LocalDate.lastDayOfMonth(): Int =
        LocalDate(year, month, 1)
            .plus(DatePeriod(months = 1))
            .minus(DatePeriod(days = 1))
            .day

    private const val DAYS_PER_WEEK = 7
    private const val MONTHS_PER_YEAR = 12
}

/**
 * The state of a series after one occurrence has been consumed.
 *
 * @property start the occurrence now at the head of the series, in local wall-clock time.
 * @property rule the remaining series, with a count-based end decremented and unreachable
 *   exceptions dropped.
 */
public data class SeriesAdvance(
    val start: LocalDateTime,
    val rule: RecurrenceRule,
)
