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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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

    @Nested
    @DisplayName("counted weekdays")
    inner class CountedWeekdays {
        @Test
        fun `every 2nd Tuesday of the month`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.MONTHLY,
                    daysOfWeek = setOf(DayOfWeek.TUESDAY),
                    weekOfMonth = 2,
                )

            val dates = localOccurrences(rule, take = 3, start = LocalDateTime(2026, 3, 10, 8, 0))

            assertEquals(listOf("2026-03-10T08:00", "2026-04-14T08:00", "2026-05-12T08:00"), dates)
        }

        @Test
        fun `the last Friday of the month is counted from the end`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.MONTHLY,
                    daysOfWeek = setOf(DayOfWeek.FRIDAY),
                    weekOfMonth = RecurrenceRule.LAST_WEEK_OF_MONTH,
                )

            val dates = localOccurrences(rule, take = 3, start = LocalDateTime(2026, 1, 30, 8, 0))

            assertEquals(listOf("2026-01-30T08:00", "2026-02-27T08:00", "2026-03-27T08:00"), dates)
        }

        @Test
        fun `a month without a 5th Monday has no occurrence rather than a clamped one`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.MONTHLY,
                    daysOfWeek = setOf(DayOfWeek.MONDAY),
                    weekOfMonth = 5,
                )

            val dates = localOccurrences(rule, take = 3, start = LocalDateTime(2026, 3, 30, 8, 0))

            assertEquals(listOf("2026-03-30T08:00", "2026-06-29T08:00", "2026-08-31T08:00"), dates)
        }
    }

    @Nested
    @DisplayName("yearly")
    inner class Yearly {
        @Test
        fun `repeats on the same date each year`() {
            val dates = localOccurrences(RecurrenceRule(RecurrenceFrequency.YEARLY), take = 3)

            assertEquals(listOf("2026-03-02T08:00", "2027-03-02T08:00", "2028-03-02T08:00"), dates)
        }

        @Test
        fun `honours an interval`() {
            val rule = RecurrenceRule(RecurrenceFrequency.YEARLY, interval = 2)

            val dates = localOccurrences(rule, take = 3)

            assertEquals(listOf("2026-03-02T08:00", "2028-03-02T08:00", "2030-03-02T08:00"), dates)
        }

        @Test
        fun `a named month later in the year is reached this year`() {
            val rule = RecurrenceRule(RecurrenceFrequency.YEARLY, monthOfYear = 9, dayOfMonth = 1)

            val dates = localOccurrences(rule, take = 2)

            assertEquals(listOf("2026-09-01T08:00", "2027-09-01T08:00"), dates)
        }

        @Test
        fun `a named month already past waits for next year`() {
            val rule = RecurrenceRule(RecurrenceFrequency.YEARLY, monthOfYear = 1, dayOfMonth = 15)

            val dates = localOccurrences(rule, take = 2)

            assertEquals(listOf("2027-01-15T08:00", "2028-01-15T08:00"), dates)
        }

        @Test
        fun `a 29 February anniversary falls back to the 28th in common years`() {
            val rule = RecurrenceRule(RecurrenceFrequency.YEARLY)
            val start = LocalDateTime(2028, 2, 29, 8, 0)

            val dates = localOccurrences(rule, take = 3, start = start)

            assertEquals(listOf("2028-02-29T08:00", "2029-02-28T08:00", "2030-02-28T08:00"), dates)
        }

        @Test
        fun `the 2nd Tuesday of a named month`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.YEARLY,
                    daysOfWeek = setOf(DayOfWeek.TUESDAY),
                    weekOfMonth = 2,
                    monthOfYear = 6,
                )

            val dates = localOccurrences(rule, take = 2)

            assertEquals(listOf("2026-06-09T08:00", "2027-06-08T08:00"), dates)
        }
    }

    @Nested
    @DisplayName("exceptions")
    inner class Exceptions {
        @Test
        fun `an excluded date produces no occurrence`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.DAILY,
                    exceptions = setOf(LocalDate(2026, 3, 3)),
                )

            val dates = localOccurrences(rule, take = 3)

            assertEquals(listOf("2026-03-02T08:00", "2026-03-04T08:00", "2026-03-05T08:00"), dates)
        }

        @Test
        fun `an excluded occurrence still counts against a bounded rule`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.DAILY,
                    exceptions = setOf(LocalDate(2026, 3, 3)),
                    end = RecurrenceEnd.AfterOccurrences(3),
                )

            val dates = localOccurrences(rule, take = 10)

            assertEquals(
                listOf("2026-03-02T08:00", "2026-03-04T08:00"),
                dates,
                "removing an occurrence must not extend the series past its closing date",
            )
        }
    }

    @Nested
    @DisplayName("advancing the series")
    inner class Advancing {
        @Test
        fun `moves the head on by exactly one occurrence`() {
            val advanced = RecurrenceCalculator.advance(RecurrenceRule(RecurrenceFrequency.DAILY), eightAm)

            assertEquals(LocalDateTime(2026, 3, 3, 8, 0), advanced?.start)
        }

        @Test
        fun `keeps the wall clock across a spring-forward gap`() {
            // 01:30 on 2026-03-29 does not exist in Europe/London; the series must still read 01:30.
            val start = LocalDateTime(2026, 3, 28, 1, 30)

            val advanced = RecurrenceCalculator.advance(RecurrenceRule(RecurrenceFrequency.DAILY), start)

            assertEquals(LocalDateTime(2026, 3, 29, 1, 30), advanced?.start)
        }

        @Test
        fun `decrements a counted end so the series is not extended`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.DAILY,
                    end = RecurrenceEnd.AfterOccurrences(3),
                )

            val advanced = requireNotNull(RecurrenceCalculator.advance(rule, eightAm))

            assertEquals(RecurrenceEnd.AfterOccurrences(2), advanced.rule.end)
            assertEquals(
                listOf(LocalDateTime(2026, 3, 3, 8, 0), LocalDateTime(2026, 3, 4, 8, 0)),
                RecurrenceCalculator.localOccurrences(advanced.rule, advanced.start).toList(),
            )
        }

        @Test
        fun `an excluded occurrence is consumed as it is skipped over`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.DAILY,
                    exceptions = setOf(LocalDate(2026, 3, 3)),
                    end = RecurrenceEnd.AfterOccurrences(4),
                )

            val advanced = requireNotNull(RecurrenceCalculator.advance(rule, eightAm))

            assertEquals(LocalDateTime(2026, 3, 4, 8, 0), advanced.start)
            assertEquals(RecurrenceEnd.AfterOccurrences(2), advanced.rule.end)
        }

        @Test
        fun `drops exceptions the series can no longer reach`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.DAILY,
                    exceptions = setOf(LocalDate(2026, 3, 1), LocalDate(2026, 3, 9)),
                )

            val advanced = requireNotNull(RecurrenceCalculator.advance(rule, eightAm))

            assertEquals(setOf(LocalDate(2026, 3, 9)), advanced.rule.exceptions)
        }

        @Test
        fun `the last occurrence of a series cannot be advanced`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.DAILY,
                    end = RecurrenceEnd.AfterOccurrences(1),
                )

            assertNull(RecurrenceCalculator.advance(rule, eightAm))
        }

        @Test
        fun `a one-off task has nothing to advance to`() {
            assertNull(RecurrenceCalculator.advance(null, eightAm))
        }

        @Test
        fun `repeated advancing visits every occurrence once and none twice`() {
            val rule = RecurrenceRule(RecurrenceFrequency.DAILY, end = RecurrenceEnd.AfterOccurrences(6))

            val visited = mutableListOf(eightAm)
            var head: LocalDateTime? = eightAm
            while (head != null) {
                val next = RecurrenceCalculator.advance(rule.advancedTo(visited.size), head)
                head = next?.start?.also { visited += it }
            }

            assertEquals(
                RecurrenceCalculator.localOccurrences(rule, eightAm).toList(),
                visited,
                "advancing must reproduce the rule's own occurrences, in order",
            )
        }

        @Test
        fun `a monthly series on the 31st keeps the 31st after a short month`() {
            val rule = RecurrenceRule(RecurrenceFrequency.MONTHLY)
            var head = LocalDateTime(2027, 1, 31, 8, 0)
            var current = rule
            val visited = mutableListOf(head)

            repeat(3) {
                val advanced = requireNotNull(RecurrenceCalculator.advance(current, head))
                head = advanced.start
                current = advanced.rule
                visited += head
            }

            assertEquals(
                listOf(
                    LocalDateTime(2027, 1, 31, 8, 0),
                    LocalDateTime(2027, 2, 28, 8, 0),
                    LocalDateTime(2027, 3, 31, 8, 0),
                    LocalDateTime(2027, 4, 30, 8, 0),
                ),
                visited,
                "a short month must borrow the last day, not redefine the series",
            )
        }

        @Test
        fun `a yearly series on 29 February returns to the 29th on the next leap year`() {
            val rule = RecurrenceRule(RecurrenceFrequency.YEARLY)

            val first = requireNotNull(RecurrenceCalculator.advance(rule, LocalDateTime(2028, 2, 29, 8, 0)))

            assertEquals(LocalDateTime(2029, 2, 28, 8, 0), first.start)
            assertEquals(
                LocalDateTime(2032, 2, 29, 8, 0),
                RecurrenceCalculator.localOccurrences(first.rule, first.start).drop(3).first(),
                "the leap day is only borrowed away for the three common years",
            )
        }

        @Test
        fun `a head that is not itself on the rule advances to the rule's first occurrence`() {
            // Created on a Monday for a Tuesday/Thursday rule: the Tuesday must not be skipped.
            val monday = LocalDateTime(2026, 3, 2, 8, 0)
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.WEEKLY,
                    daysOfWeek = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
                    end = RecurrenceEnd.AfterOccurrences(2),
                )

            val advanced = requireNotNull(RecurrenceCalculator.advance(rule, monday))

            assertEquals(LocalDateTime(2026, 3, 3, 8, 0), advanced.start)
            assertEquals(RecurrenceEnd.AfterOccurrences(2), advanced.rule.end, "no occurrence was consumed yet")
        }

        private fun RecurrenceRule.advancedTo(consumed: Int): RecurrenceRule =
            copy(end = RecurrenceEnd.AfterOccurrences((end as RecurrenceEnd.AfterOccurrences).count - consumed + 1))
    }

    @Nested
    @DisplayName("timezone travel")
    inner class TimezoneTravel {
        @ParameterizedTest
        @CsvSource(
            "Europe/London, 2026-03-28T08:00, 2026-03-29T08:00",
            "America/New_York, 2026-03-07T08:00, 2026-03-08T08:00",
            "Australia/Sydney, 2026-04-04T08:00, 2026-04-05T08:00",
            "Pacific/Auckland, 2026-09-26T08:00, 2026-09-27T08:00",
            "Asia/Tokyo, 2026-03-28T08:00, 2026-03-29T08:00",
        )
        fun `a daily reminder keeps its local time across every kind of clock change`(
            zoneId: String,
            start: String,
            expectedNext: String,
        ) {
            val zone = TimeZone.of(zoneId)
            val rule = RecurrenceRule(RecurrenceFrequency.DAILY)

            val next = RecurrenceCalculator.occurrences(rule, LocalDateTime.parse(start), zone).drop(1).first()

            assertEquals(expectedNext, next.toLocalDateTime(zone).toString())
        }

        @Test
        fun `the same rule read in another zone keeps the wall-clock time the user chose`() {
            val tokyo = TimeZone.of("Asia/Tokyo")
            val rule = RecurrenceRule(RecurrenceFrequency.DAILY)

            val inTokyo = RecurrenceCalculator.occurrences(rule, eightAm, tokyo).take(2).toList()

            assertEquals(
                listOf("2026-03-02T08:00", "2026-03-03T08:00"),
                inTokyo.map { it.toLocalDateTime(tokyo).toString() },
            )
            assertTrue(
                inTokyo.first() < RecurrenceCalculator.occurrences(rule, eightAm, london).first(),
                "08:00 in Tokyo happens before 08:00 in London on the same date",
            )
        }
    }

    private fun localOccurrences(
        rule: RecurrenceRule,
        take: Int,
        start: LocalDateTime = eightAm,
    ): List<String> =
        RecurrenceCalculator
            .occurrences(rule, start, london)
            .take(take)
            .map { it.toLocalDateTime(london).toString() }
            .toList()

    private fun LocalDateTime.toInstantIn(zone: TimeZone): Instant =
        RecurrenceCalculator.occurrences(null, this, zone).first()
}
