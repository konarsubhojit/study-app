package dev.studyflow.app.widget

import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.TimeAnchor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TimerWidgetSnapshotTest {
    private val bootId = BootId("boot-1")
    private val start =
        TimeAnchor(
            uptime = 10.minutes,
            wallClock = Instant.parse("2026-03-01T09:00:00Z"),
            bootId = bootId,
        )

    @Test
    fun `an idle timer offers nothing to count`() {
        val snapshot = TimerState.Idle.toWidgetSnapshot(start)

        assertEquals(TimerWidgetPhase.IDLE, snapshot.phase)
        assertFalse(snapshot.isActive, "an idle timer is not active")
    }

    @Test
    fun `a stopped session reads as idle because its log is closed`() {
        val stopped =
            TimerState.Stopped(
                sessionId = "session",
                settled = 25.minutes,
                unverified = kotlin.time.Duration.ZERO,
                lastSequence = 4,
            )

        assertEquals(TimerWidgetPhase.IDLE, stopped.toWidgetSnapshot(start).phase)
    }

    @Test
    fun `a running session counts the open interval and anchors the chronometer to its start`() {
        val running =
            TimerState.Running(
                sessionId = "session",
                settled = 5.minutes,
                unverified = kotlin.time.Duration.ZERO,
                lastSequence = 2,
                openedAt = start,
            )
        val now = start.copy(uptime = start.uptime + 30.seconds, wallClock = start.wallClock + 30.seconds)

        val snapshot = running.toWidgetSnapshot(now)

        assertEquals(TimerWidgetPhase.RUNNING, snapshot.phase)
        assertEquals(5.minutes + 30.seconds, snapshot.elapsed)
        // The launcher's Chronometer counts up from this monotonic reading, so it must be exactly
        // "now minus what has been counted" — anything else and the home screen drifts from the log.
        assertEquals((now.uptime - snapshot.elapsed).inWholeMilliseconds, snapshot.chronometerBaseMillis)
    }

    @Test
    fun `a paused session shows settled time and does not count`() {
        val paused =
            TimerState.Paused(
                sessionId = "session",
                settled = 12.minutes,
                unverified = kotlin.time.Duration.ZERO,
                lastSequence = 3,
            )

        val snapshot = paused.toWidgetSnapshot(start.copy(uptime = start.uptime + 1.hours))

        assertEquals(TimerWidgetPhase.PAUSED, snapshot.phase)
        assertEquals(12.minutes, snapshot.elapsed)
        assertFalse(snapshot.isRunning, "a paused timer must not be reported as running")
    }

    @Test
    fun `time the device could not verify is surfaced rather than folded into the total`() {
        val paused =
            TimerState.Paused(
                sessionId = "session",
                settled = 12.minutes,
                unverified = 40.minutes,
                lastSequence = 3,
            )

        val snapshot = paused.toWidgetSnapshot(start)

        assertEquals(12.minutes, snapshot.elapsed)
        assertTrue(snapshot.hasUnverifiedTime, "unverified time must be flagged on the widget")
    }

    @Test
    fun `elapsed time reads like a stopwatch`() {
        assertEquals("00:09", formatElapsed(9.seconds))
        assertEquals("04:09", formatElapsed(4.minutes + 9.seconds))
        assertEquals("1:04:09", formatElapsed(1.hours + 4.minutes + 9.seconds))
    }
}
