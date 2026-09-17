package dev.studyflow.core.common.time

import dev.studyflow.core.model.BootId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ClocksTest {
    @Test
    fun `anchor combines injected wall monotonic and boot sources`() {
        val wallClock = Clock { Instant.parse("2026-03-01T09:00:00Z") }
        val elapsedRealtime = ElapsedRealtimeSource { 3.hours }
        val bootId = BootIdProvider { BootId("boot-42") }

        val anchor = DefaultAnchoredClock(wallClock, elapsedRealtime, bootId).anchor()

        assertEquals(Instant.parse("2026-03-01T09:00:00Z"), anchor.wallClock)
        assertEquals(3.hours, anchor.uptime)
        assertEquals(BootId("boot-42"), anchor.bootId)
    }
}
