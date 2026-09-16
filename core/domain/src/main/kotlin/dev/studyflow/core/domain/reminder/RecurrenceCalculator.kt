package dev.studyflow.core.domain.reminder

import dev.studyflow.core.model.RecurrenceEnd
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.RecurrenceRule
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
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
    ): Sequence<Instant> {
        if (rule == null) return sequenceOf(start.toInstant(zone))

        val dates =
            when (rule.frequency) {
                RecurrenceFrequency.DAILY -> dailyDates(start.date, rule.interval)
                RecurrenceFrequency.WEEKLY -> weeklyDates(start.date, rule.interval, rule)
                RecurrenceFrequency.MONTHLY -> monthlyDates(start.date, rule.interval, rule.dayOfMonth)
            }

        val bounded =
            when (val end = rule.end) {
                RecurrenceEnd.Never -> dates
                is RecurrenceEnd.AfterOccurrences -> dates.take(end.count)
                is RecurrenceEnd.OnDate -> dates.takeWhile { it <= end.date }
            }

        // Resolving to an instant last is what makes the whole thing DST-safe: the local time of
        // day is preserved, and kotlinx-datetime applies the zone's rules for that specific date.
        return bounded.map { LocalDateTime(it, start.time).toInstant(zone) }
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
        interval: Int,
        dayOfMonth: Int?,
    ): Sequence<LocalDate> {
        val day = dayOfMonth ?: start.day
        val firstOfMonth = LocalDate(start.year, start.month, 1)
        return generateSequence(firstOfMonth) { it.plus(interval, DateTimeUnit.MONTH) }
            .map { it.withDayClampedToMonth(day) }
            .dropWhile { it < start }
    }

    /**
     * Clamps a day-of-month to the length of the month.
     *
     * "The 31st of every month" has to mean *something* in February. Clamping to the 28th/29th
     * keeps the reminder monthly; skipping the short months quietly drops a third of them.
     */
    private fun LocalDate.withDayClampedToMonth(day: Int): LocalDate {
        val lastDay =
            LocalDate(year, month, 1)
                .plus(DatePeriod(months = 1))
                .minus(DatePeriod(days = 1))
                .day
        return LocalDate(year, month, minOf(day, lastDay))
    }

    private const val DAYS_PER_WEEK = 7
}
