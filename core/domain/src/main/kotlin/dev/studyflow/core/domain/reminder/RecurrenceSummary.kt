package dev.studyflow.core.domain.reminder

import dev.studyflow.core.model.RecurrenceEnd
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.RecurrenceRule
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month

/**
 * Says what a [RecurrenceRule] means, in words.
 *
 * ### Why the text is built here rather than in the UI
 *
 * A rule is open-ended — any interval, any set of weekdays, a counted weekday, an end date — so it
 * cannot be reduced to a fixed set of string resources the way a closed error model can. Building
 * the sentence next to the engine that interprets the rule is the only way to keep the description
 * and the behaviour from drifting apart, and it makes "does this rule say what the user thinks it
 * says?" a unit test rather than a screenshot review.
 *
 * The wording is English and is the phrasing a screen shows until it is localised; a translation
 * would substitute the same parts (frequency, days, end) in its own grammar.
 */
public object RecurrenceSummary {
    /**
     * A one-line description such as "Every weekday at 18:00, 10 times" or "Every month on the 2nd Tuesday".
     *
     * @param rule the rule to describe; `null` is a task that happens once.
     * @param start the occurrence the series is anchored to, which supplies whatever the rule
     *   leaves implicit — the weekday of a weekly rule, the day of a monthly one, the time of day.
     */
    public fun describe(
        rule: RecurrenceRule?,
        start: LocalDateTime? = null,
    ): String {
        if (rule == null) return "Does not repeat"
        return buildString {
            append(repetition(rule, start?.date))
            append(timeOfDay(start?.time))
            append(ending(rule.end))
        }
    }

    private fun repetition(
        rule: RecurrenceRule,
        start: LocalDate?,
    ): String =
        when (rule.frequency) {
            RecurrenceFrequency.DAILY -> every(rule.interval, "day", "days")
            RecurrenceFrequency.WEEKLY -> weekly(rule, start)
            RecurrenceFrequency.MONTHLY -> withDay(every(rule.interval, "month", "months"), rule, start)
            RecurrenceFrequency.YEARLY -> yearly(rule, start)
        }

    private fun weekly(
        rule: RecurrenceRule,
        start: LocalDate?,
    ): String {
        val days = rule.daysOfWeek.ifEmpty { setOfNotNull(start?.dayOfWeek) }
        val named = days.sortedBy(DayOfWeek::ordinal).joinToString(transform = ::dayName)
        return when {
            days.isEmpty() -> every(rule.interval, "week", "weeks")
            days == WEEKDAYS && rule.interval == 1 -> "Every weekday"
            rule.interval == 1 -> "Every $named"
            else -> "${every(rule.interval, "week", "weeks")} on $named"
        }
    }

    private fun yearly(
        rule: RecurrenceRule,
        start: LocalDate?,
    ): String {
        val every = every(rule.interval, "year", "years")
        val month = rule.monthOfYear?.let { Month(it) } ?: start?.month
        val monthName = month?.let(::monthName)
        val day = rule.dayOfMonth ?: start?.day
        return when {
            monthName == null -> every
            rule.weekOfMonth != null -> "$every on the ${countedWeekday(rule)} of $monthName"
            day == null -> "$every in $monthName"
            else -> "$every on $day $monthName"
        }
    }

    private fun withDay(
        every: String,
        rule: RecurrenceRule,
        start: LocalDate?,
    ): String =
        when {
            rule.weekOfMonth != null -> {
                "$every on the ${countedWeekday(rule)}"
            }

            else -> {
                val day = rule.dayOfMonth ?: start?.day
                if (day == null) every else "$every on the ${ordinal(day)}"
            }
        }

    /** "2nd Tuesday", or "last Tuesday" for [RecurrenceRule.LAST_WEEK_OF_MONTH]. */
    private fun countedWeekday(rule: RecurrenceRule): String {
        val week = requireNotNull(rule.weekOfMonth)
        val position = if (week == RecurrenceRule.LAST_WEEK_OF_MONTH) "last" else ordinal(week)
        return "$position ${dayName(rule.daysOfWeek.first())}"
    }

    private fun every(
        interval: Int,
        singular: String,
        plural: String,
    ): String = if (interval == 1) "Every $singular" else "Every $interval $plural"

    private fun ending(end: RecurrenceEnd): String =
        when (end) {
            RecurrenceEnd.Never -> ""
            is RecurrenceEnd.AfterOccurrences -> if (end.count == 1) ", once" else ", ${end.count} times"
            is RecurrenceEnd.OnDate -> ", until ${end.date.day} ${monthName(end.date.month)} ${end.date.year}"
        }

    /** Midnight is left unsaid: it is what an all-day task is anchored to, not a time the user set. */
    private fun timeOfDay(time: LocalTime?): String =
        if (time == null || time == LocalTime(0, 0)) {
            ""
        } else {
            " at ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
        }

    private fun ordinal(value: Int): String = "$value${ORDINAL_SUFFIXES[suffixIndex(value)]}"

    private fun suffixIndex(value: Int): Int =
        when {
            value % HUNDRED in TEENS -> 0
            else -> value % TEN
        }.coerceAtMost(ORDINAL_SUFFIXES.lastIndex)

    private fun dayName(day: DayOfWeek): String = titleCase(day.name)

    private fun monthName(month: Month): String = titleCase(month.name)

    private fun titleCase(name: String): String = name.lowercase().replaceFirstChar(Char::uppercaseChar)

    private val WEEKDAYS =
        setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        )

    // Index 0 is the default "th", which also covers 11th–13th; 1st, 2nd and 3rd are the exceptions.
    private val ORDINAL_SUFFIXES = listOf("th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th")
    private val TEENS = 11..13
    private const val TEN = 10
    private const val HUNDRED = 100
}
