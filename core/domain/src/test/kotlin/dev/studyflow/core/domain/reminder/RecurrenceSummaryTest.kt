package dev.studyflow.core.domain.reminder

import dev.studyflow.core.model.RecurrenceEnd
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.RecurrenceRule
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RecurrenceSummary")
class RecurrenceSummaryTest {
    private val sixPm = LocalDateTime(2026, 3, 2, 18, 0)

    @Test
    fun `a task with no rule happens once`() {
        assertEquals("Does not repeat", RecurrenceSummary.describe(null, sixPm))
    }

    @Test
    fun `every weekday at six`() {
        val rule =
            RecurrenceRule(
                frequency = RecurrenceFrequency.WEEKLY,
                daysOfWeek =
                    setOf(
                        DayOfWeek.MONDAY,
                        DayOfWeek.TUESDAY,
                        DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY,
                        DayOfWeek.FRIDAY,
                    ),
            )

        assertEquals("Every weekday at 18:00", RecurrenceSummary.describe(rule, sixPm))
    }

    @Test
    fun `named weekdays are listed`() {
        val rule =
            RecurrenceRule(
                frequency = RecurrenceFrequency.WEEKLY,
                daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
            )

        assertEquals("Every Monday, Thursday at 18:00", RecurrenceSummary.describe(rule, sixPm))
    }

    @Test
    fun `a weekly rule with no days named borrows the start weekday`() {
        val rule = RecurrenceRule(RecurrenceFrequency.WEEKLY)

        assertEquals("Every Monday at 18:00", RecurrenceSummary.describe(rule, sixPm))
    }

    @Test
    fun `an interval is spelled out`() {
        val rule =
            RecurrenceRule(
                frequency = RecurrenceFrequency.WEEKLY,
                interval = 2,
                daysOfWeek = setOf(DayOfWeek.TUESDAY),
            )

        assertEquals("Every 2 weeks on Tuesday at 18:00", RecurrenceSummary.describe(rule, sixPm))
    }

    @Test
    fun `a daily rule needs no start to be described`() {
        val rule = RecurrenceRule(RecurrenceFrequency.DAILY, interval = 3)

        assertEquals("Every 3 days", RecurrenceSummary.describe(rule))
    }

    @Test
    fun `a counted weekday reads as the user picked it`() {
        val rule =
            RecurrenceRule(
                frequency = RecurrenceFrequency.MONTHLY,
                daysOfWeek = setOf(DayOfWeek.TUESDAY),
                weekOfMonth = 2,
            )

        assertEquals("Every month on the 2nd Tuesday at 18:00", RecurrenceSummary.describe(rule, sixPm))
    }

    @Test
    fun `the last weekday of the month is named, not numbered`() {
        val rule =
            RecurrenceRule(
                frequency = RecurrenceFrequency.MONTHLY,
                daysOfWeek = setOf(DayOfWeek.FRIDAY),
                weekOfMonth = RecurrenceRule.LAST_WEEK_OF_MONTH,
            )

        assertEquals("Every month on the last Friday", RecurrenceSummary.describe(rule))
    }

    @Test
    fun `a day of the month is ordinal`() {
        val rule = RecurrenceRule(RecurrenceFrequency.MONTHLY, dayOfMonth = 31)

        assertEquals("Every month on the 31st", RecurrenceSummary.describe(rule))
    }

    @Test
    fun `a monthly rule borrows the day from the start date`() {
        val rule = RecurrenceRule(RecurrenceFrequency.MONTHLY, interval = 3)

        assertEquals("Every 3 months on the 2nd at 18:00", RecurrenceSummary.describe(rule, sixPm))
    }

    @Test
    fun `a yearly rule names the date`() {
        val rule = RecurrenceRule(RecurrenceFrequency.YEARLY)

        assertEquals("Every year on 2 March at 18:00", RecurrenceSummary.describe(rule, sixPm))
    }

    @Test
    fun `a yearly counted weekday names the month`() {
        val rule =
            RecurrenceRule(
                frequency = RecurrenceFrequency.YEARLY,
                daysOfWeek = setOf(DayOfWeek.TUESDAY),
                weekOfMonth = 2,
                monthOfYear = 6,
            )

        assertEquals("Every year on the 2nd Tuesday of June", RecurrenceSummary.describe(rule))
    }

    @Test
    fun `a counted end is spelled out`() {
        val rule =
            RecurrenceRule(
                frequency = RecurrenceFrequency.DAILY,
                end = RecurrenceEnd.AfterOccurrences(10),
            )

        assertEquals("Every day at 18:00, 10 times", RecurrenceSummary.describe(rule, sixPm))
    }

    @Test
    fun `a closing date is spelled out`() {
        val rule =
            RecurrenceRule(
                frequency = RecurrenceFrequency.DAILY,
                end = RecurrenceEnd.OnDate(LocalDate(2026, 6, 3)),
            )

        assertEquals("Every day, until 3 June 2026", RecurrenceSummary.describe(rule))
    }

    @Test
    fun `an all-day task says nothing about midnight`() {
        val rule = RecurrenceRule(RecurrenceFrequency.DAILY)

        assertEquals("Every day", RecurrenceSummary.describe(rule, LocalDateTime(2026, 3, 2, 0, 0)))
    }
}
