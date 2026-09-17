package dev.studyflow.feature.tasks

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@DisplayName("QuickAddDueDate")
class QuickAddDueDateTest {
    private val now = Instant.parse("2026-03-01T09:00:00Z")
    private val zone = TimeZone.UTC

    @Test
    fun `none resolves to no due date at all`() {
        assertNull(QuickAddDueDate.None.resolve(now, zone))
    }

    @Test
    fun `today resolves to the current local date`() {
        assertEquals(LocalDate(2026, 3, 1), QuickAddDueDate.Today.resolve(now, zone))
    }

    @Test
    fun `tomorrow resolves to one day after the current local date`() {
        assertEquals(LocalDate(2026, 3, 2), QuickAddDueDate.Tomorrow.resolve(now, zone))
    }

    @Test
    fun `next week resolves to seven days after the current local date`() {
        assertEquals(LocalDate(2026, 3, 8), QuickAddDueDate.NextWeek.resolve(now, zone))
    }

    @Test
    fun `custom resolves to the date the user picked, independent of the clock`() {
        val picked = LocalDate(2026, 12, 25)

        assertEquals(picked, QuickAddDueDate.Custom(picked).resolve(now, zone))
    }

    @Test
    fun `resolution follows the local day in the given time zone, not UTC`() {
        // 22:00 UTC has already rolled into the next local day fourteen hours ahead of UTC.
        val lateInTheDay = Instant.parse("2026-03-01T22:00:00Z")
        val aheadOfUtc = TimeZone.of("Pacific/Kiritimati")

        assertEquals(LocalDate(2026, 3, 2), QuickAddDueDate.Today.resolve(lateInTheDay, aheadOfUtc))
    }

    @Test
    fun `an all-day due date sits at local midnight`() {
        val date = LocalDate(2026, 3, 1)

        assertEquals(0, date.atAllDayMidnight().hour)
        assertEquals(0, date.atAllDayMidnight().minute)
        assertEquals(date, date.atAllDayMidnight().date)
    }
}
