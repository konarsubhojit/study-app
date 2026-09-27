package dev.studyflow.app.timer

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TimerAlarmFallbackTest {
    @Test
    fun `permission denial cancels the exact alarm and schedules inexactly without escaping`() {
        val calls = mutableListOf<String>()

        assertDoesNotThrow {
            scheduleTimerAlarmWithInexactFallback(
                scheduleExact = {
                    calls += "exact"
                    throw SecurityException("exact alarm permission denied")
                },
                onExactAlarmDenied = {
                    calls += "cancel"
                },
                scheduleInexact = { calls += "inexact" },
            )
        }

        assertEquals(listOf("exact", "cancel", "inexact"), calls)
    }
}
