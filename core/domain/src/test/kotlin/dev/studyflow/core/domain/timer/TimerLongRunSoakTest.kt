package dev.studyflow.core.domain.timer

import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.SessionEventType
import dev.studyflow.core.testing.time.FakeDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Tag("soak")
class TimerLongRunSoakTest {
    @Test
    fun `six simulated hours do not require ticks or accumulate drift`() {
        val device = FakeDevice()
        val events = mutableListOf<SessionEvent>()

        events += event("start", SessionEventType.STARTED, 0, device)
        repeat(6 * 60) {
            device.advance(1.minutes)
            TimerEngine.elapsedAt(TimerEngine.fold(events), device.anchor())
        }
        events += event("stop", SessionEventType.STOPPED, 1, device)

        val state = TimerEngine.fold(events)
        val elapsed = TimerEngine.elapsedAt(state, device.anchor())
        assertEquals(6.hours, elapsed.counted)
        assertEquals(2, events.size, "long sessions must not be represented by per-tick events")
    }

    private fun event(
        id: String,
        type: SessionEventType,
        sequence: Long,
        device: FakeDevice,
    ): SessionEvent =
        SessionEvent(
            id = id,
            sessionId = "session-soak",
            type = type,
            anchor = device.anchor(),
            sequence = sequence,
        )
}
