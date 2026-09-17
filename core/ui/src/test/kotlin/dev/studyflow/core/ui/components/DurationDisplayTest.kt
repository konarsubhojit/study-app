package dev.studyflow.core.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class DurationDisplayTest {
    @Test
    fun `zero duration is displayed in minutes`() {
        assertEquals(DurationDisplay(visual = "0 min", spoken = "0 minutes"), 0.minutes.toDisplayStrings())
    }

    @Test
    fun `whole hours omit the zero minutes`() {
        assertEquals(DurationDisplay(visual = "2 h", spoken = "2 hours"), 2.hours.toDisplayStrings())
    }

    @Test
    fun `hours and minutes are both displayed`() {
        assertEquals(
            DurationDisplay(visual = "1 h 30 min", spoken = "1 hour 30 minutes"),
            90.minutes.toDisplayStrings(),
        )
    }

    @Test
    fun `negative durations are displayed as zero`() {
        assertEquals(DurationDisplay(visual = "0 min", spoken = "0 minutes"), (-1).minutes.toDisplayStrings())
    }
}
