package dev.studyflow.core.model

import kotlinx.datetime.DayOfWeek
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RecurrenceRule")
class RecurrenceRuleTest {
    @Test
    fun `a counted weekday needs exactly one weekday to count`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecurrenceRule(
                frequency = RecurrenceFrequency.MONTHLY,
                daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                weekOfMonth = 2,
            )
        }
    }

    @Test
    fun `a day cannot be picked by number and by counted weekday at once`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecurrenceRule(
                frequency = RecurrenceFrequency.MONTHLY,
                daysOfWeek = setOf(DayOfWeek.MONDAY),
                weekOfMonth = 2,
                dayOfMonth = 14,
            )
        }
    }

    @Test
    fun `weekdays outside a weekly rule only mean something with a count`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecurrenceRule(frequency = RecurrenceFrequency.MONTHLY, daysOfWeek = setOf(DayOfWeek.MONDAY))
        }
    }

    @Test
    fun `a named month only applies to a yearly rule`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecurrenceRule(frequency = RecurrenceFrequency.MONTHLY, monthOfYear = 6)
        }
    }

    @Test
    fun `a weekly rule cannot pick a day of the month`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecurrenceRule(frequency = RecurrenceFrequency.WEEKLY, dayOfMonth = 14)
        }
    }

    @Test
    fun `the last weekday of the month is a valid count`() {
        val rule =
            RecurrenceRule(
                frequency = RecurrenceFrequency.MONTHLY,
                daysOfWeek = setOf(DayOfWeek.FRIDAY),
                weekOfMonth = RecurrenceRule.LAST_WEEK_OF_MONTH,
            )

        assertEquals(RecurrenceRule.LAST_WEEK_OF_MONTH, rule.weekOfMonth)
    }

    @Test
    fun `a yearly rule may name both a month and a counted weekday`() {
        val rule =
            RecurrenceRule(
                frequency = RecurrenceFrequency.YEARLY,
                daysOfWeek = setOf(DayOfWeek.TUESDAY),
                weekOfMonth = 2,
                monthOfYear = 6,
            )

        assertEquals(6, rule.monthOfYear)
    }
}
