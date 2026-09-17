package dev.studyflow.core.scheduling

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pure-function tests for [AlarmPlaybackPolicy] (issue #48) — no Robolectric needed, since nothing
 * here touches `AudioManager`, `Vibrator` or a clock.
 */
class AlarmPlaybackPolicyTest {
    @Test
    fun `volume starts audible rather than silent`() {
        val volumeAtStart = AlarmPlaybackPolicy.volumeAt(Duration.ZERO)

        assertTrue(volumeAtStart > 0f, "volume at start was $volumeAtStart")
    }

    @Test
    fun `volume ramps up to full by the end of the escalation window`() {
        val volume = AlarmPlaybackPolicy.volumeAt(AlarmPlaybackPolicy.ESCALATION_DURATION)

        assertEquals(1f, volume)
    }

    @Test
    fun `volume keeps increasing partway through the escalation window`() {
        val early = AlarmPlaybackPolicy.volumeAt(2.seconds)
        val later = AlarmPlaybackPolicy.volumeAt(10.seconds)

        assertTrue(later > early, "expected $later to be louder than $early")
    }

    @Test
    fun `volume stays at full past the escalation window`() {
        val volume = AlarmPlaybackPolicy.volumeAt(AlarmPlaybackPolicy.ESCALATION_DURATION + 30.seconds)

        assertEquals(1f, volume)
    }

    @Test
    fun `has not timed out before the timeout duration`() {
        assertFalse(AlarmPlaybackPolicy.hasTimedOut(AlarmPlaybackPolicy.TIMEOUT_DURATION - 1.seconds))
    }

    @Test
    fun `has timed out once the timeout duration elapses`() {
        assertTrue(AlarmPlaybackPolicy.hasTimedOut(AlarmPlaybackPolicy.TIMEOUT_DURATION))
    }

    @Test
    fun `an unattended alarm auto-snoozes rather than dismissing outright`() {
        assertEquals(ReminderActionKind.SNOOZE, AlarmPlaybackPolicy.OUTCOME_AFTER_TIMEOUT)
    }
}

