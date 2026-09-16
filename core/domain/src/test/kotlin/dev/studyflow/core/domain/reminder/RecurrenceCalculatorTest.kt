package dev.studyflow.core.domain.reminder

import dev.studyflow.core.model.RecurrenceEnd
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.RecurrenceRule
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@DisplayName("RecurrenceCalculator")
class RecurrenceCalculatorTest {
    private val london = TimeZone.of("Europe/London")
    private val eightAm = LocalDateTime(2026, 3, 2, 8, 0)

    @Nested
    @DisplayName("daily")
    inner class Daily {
        @Test
        fun `repeats every day at the same local time`() {
            val dates = localOccurrences(RecurrenceRule(RecurrenceFrequency.DAILY), take = 3)

            assertEquals(listOf("2026-03-02T08:00", "2026-03-03T08:00", "2026-03-04T08:00"), dates)
        }

        @Test
        fun `honours an interval`() {
            val dates = localOccurrences(RecurrenceRule(RecurrenceFrequency.DAILY, interval = 3), take = 3)

            assertEquals(listOf("2026-03-02T08:00", "2026-03-05T08:00", "2026-03-08T08:00"), dates)
        }
    }

    @Nested
    @DisplayName("weekly")
    inner class Weekly {
        @Test
        fun `defaults to the weekday of the start date`() {
            val dates = localOccurrences(RecurrenceRule(RecurrenceFrequency.WEEKLY), take = 3)

            assertEquals(listOf("2026-03-02T08:00", "2026-03-09T08:00", "2026-03-16T08:00"), dates)
        }

        @Test
        fun `expands multiple weekdays in order`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.WEEKLY,
                    daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                )

            val dates = localOccurrences(rule, take = 4)

            assertEquals(
                listOf("2026-03-02T08:00", "2026-03-04T08:00", "2026-03-06T08:00", "2026-03-09T08:00"),
                dates,
            )
        }

        @Test
        fun `a fortnightly rule stays in phase with the start week`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.WEEKLY,
                    interval = 2,
                    daysOfWeek = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
                )

            val dates = localOccurrences(rule, take = 4)

            assertEquals(
                listOf("2026-03-03T08:00", "2026-03-05T08:00", "2026-03-17T08:00", "2026-03-19T08:00"),
                dates,
            )
        }
    }

    @Nested
    @DisplayName("monthly")
    inner class Monthly {
        @Test
        fun `repeats on the same day each month`() {
            val dates = localOccurrences(RecurrenceRule(RecurrenceFrequency.MONTHLY), take = 3)

            assertEquals(listOf("2026-03-02T08:00", "2026-04-02T08:00", "2026-05-02T08:00"), dates)
        }

        @Test
        fun `the 31st clamps to the last day of shorter months instead of being skipped`() {
            val rule = RecurrenceRule(RecurrenceFrequency.MONTHLY, dayOfMonth = 31)
            val start = LocalDateTime(2026, 1, 31, 8, 0)

            val dates =
                RecurrenceCalculator
                    .occurrences(rule, start, london)
                    .take(4)
                    .map { it.toLocalDateTime(london).toString() }
                    .toList()

            assertEquals(
                listOf("2026-01-31T08:00", "2026-02-28T08:00", "2026-03-31T08:00", "2026-04-30T08:00"),
                dates,
            )
        }

        @Test
        fun `a leap year February gets the 29th`() {
            val rule = RecurrenceRule(RecurrenceFrequency.MONTHLY, dayOfMonth = 30)
            val start = LocalDateTime(2028, 1, 30, 8, 0)

            val dates =
                RecurrenceCalculator
                    .occurrences(rule, start, london)
                    .take(2)
                    .map { it.toLocalDateTime(london).toString() }
                    .toList()

            assertEquals(listOf("2028-01-30T08:00", "2028-02-29T08:00"), dates)
        }
    }

    @Nested
    @DisplayName("daylight saving time")
    inner class DaylightSaving {
        @Test
        fun `a daily 8am reminder stays at 8am across the spring forward`() {
            // Europe/London springs forward at 01:00 on 2026-03-29.
            val rule = RecurrenceRule(RecurrenceFrequency.DAILY)
            val start = LocalDateTime(2026, 3, 28, 8, 0)

            val dates =
                RecurrenceCalculator
                    .occurrences(rule, start, london)
                    .take(3)
                    .map { it.toLocalDateTime(london).toString() }
                    .toList()

            assertEquals(listOf("2026-03-28T08:00", "2026-03-29T08:00", "2026-03-30T08:00"), dates)
        }

        @Test
        fun `the real interval across the spring forward is 23 hours, not 24`() {
            val rule = RecurrenceRule(RecurrenceFrequency.DAILY)
            val start = LocalDateTime(2026, 3, 28, 8, 0)

            val (first, second) = RecurrenceCalculator.occurrences(rule, start, london).take(2).toList()

            assertEquals(23.hours, second - first, "adding 24h to an instant would fire an hour late")
        }

        @Test
        fun `the real interval across the autumn fall back is 25 hours`() {
            // Europe/London falls back at 02:00 on 2026-10-25.
            val rule = RecurrenceRule(RecurrenceFrequency.DAILY)
            val start = LocalDateTime(2026, 10, 24, 8, 0)

            val (first, second) = RecurrenceCalculator.occurrences(rule, start, london).take(2).toList()

            assertEquals(25.hours, second - first)
        }

        @Test
        fun `a reminder inside the spring-forward gap still resolves to a real instant`() {
            // 01:30 on 2026-03-29 does not exist in Europe/London.
            val rule = RecurrenceRule(RecurrenceFrequency.DAILY)
            val start = LocalDateTime(2026, 3, 28, 1, 30)

            val occurrences = RecurrenceCalculator.occurrences(rule, start, london).take(2).toList()

            assertEquals(2, occurrences.size)
            assertTrue(occurrences[1] > occurrences[0], "the skipped hour must not produce a time-travelling alarm")
        }
    }

    @Nested
    @DisplayName("ending")
    inner class Ending {
        @Test
        fun `stops after a fixed number of occurrences`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.DAILY,
                    end = RecurrenceEnd.AfterOccurrences(3),
                )

            assertEquals(3, RecurrenceCalculator.occurrences(rule, eightAm, london).count())
        }

        @Test
        fun `stops on a closing date, inclusive`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.DAILY,
                    end = RecurrenceEnd.OnDate(LocalDate(2026, 3, 4)),
                )

            val dates = localOccurrences(rule, take = 10)

            assertEquals(listOf("2026-03-02T08:00", "2026-03-03T08:00", "2026-03-04T08:00"), dates)
        }

        @Test
        fun `an exhausted rule has no next occurrence`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.DAILY,
                    end = RecurrenceEnd.AfterOccurrences(2),
                )

            val next =
                RecurrenceCalculator.nextOccurrence(
                    rule = rule,
                    start = eightAm,
                    zone = london,
                    after = Instant.parse("2027-01-01T00:00:00Z"),
                )

            assertNull(next)
        }
    }

    @Nested
    @DisplayName("next occurrence")
    inner class Next {
        @Test
        fun `a null rule fires exactly once`() {
            val occurrences = RecurrenceCalculator.occurrences(null, eightAm, london).toList()

            assertEquals(1, occurrences.size)
        }

        @Test
        fun `skips occurrences that have already passed`() {
            val next =
                RecurrenceCalculator.nextOccurrence(
                    rule = RecurrenceRule(RecurrenceFrequency.DAILY),
                    start = eightAm,
                    zone = london,
                    after = Instant.parse("2026-03-05T09:00:00Z"),
                )

            assertEquals("2026-03-06T08:00", next?.toLocalDateTime(london)?.toString())
        }

        @Test
        fun `an occurrence exactly at the cutoff is treated as past`() {
            val start = eightAm.toInstantIn(london)

            val next =
                RecurrenceCalculator.nextOccurrence(
                    rule = RecurrenceRule(RecurrenceFrequency.DAILY),
                    start = eightAm,
                    zone = london,
                    after = start,
                )

            assertEquals("2026-03-03T08:00", next?.toLocalDateTime(london)?.toString())
        }
    }

    private fun localOccurrences(
        rule: RecurrenceRule,
        take: Int,
    ): List<String> =
        RecurrenceCalculator
            .occurrences(rule, eightAm, london)
            .take(take)
            .map { it.toLocalDateTime(london).toString() }
            .toList()

    private fun LocalDateTime.toInstantIn(zone: TimeZone): Instant = RecurrenceCalculator.occurrences(null, this, zone).first()
}
