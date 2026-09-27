package dev.studyflow.core.scheduling

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ExactAlarmFallbackTest {
    @Test
    fun `alarm clock permission denial cancels the exact operation and schedules inexactly`() {
        val denied = SecurityException("exact alarm permission denied")
        val calls = mutableListOf<String>()

        val outcome =
            scheduleExactAlarmWithFallback(
                scheduleExact = {
                    calls += "alarm-clock"
                    throw denied
                },
                onExactAlarmDenied = { exception ->
                    assertSame(denied, exception)
                    calls += "denied"
                },
                cancel = { calls += "cancel" },
                scheduleInexact = { calls += "inexact" },
            )

        assertEquals(PlatformScheduleOutcome.EXACT_ALARM_DENIED_FALLBACK_TO_INEXACT, outcome)
        assertEquals(listOf("alarm-clock", "denied", "cancel", "inexact"), calls)
    }
}
