package dev.studyflow.core.domain.timer

import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.SessionEventType
import dev.studyflow.core.testing.time.FakeDevice
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * These tests are the evidence for the claim that the stopwatch survives anything the platform
 * throws at it. Each nested class corresponds to a failure mode that has sunk real timer apps.
 */
@DisplayName("TimerEngine")
class TimerEngineTest {
    @Nested
    @DisplayName("measuring")
    inner class Measuring {
        @Test
        fun `elapsed grows with the monotonic clock while running`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(25.minutes)

            assertEquals(25.minutes, log.elapsed().counted)
        }

        @Test
        fun `elapsed is exact after ten minutes without refresh ticks`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(10.minutes)

            assertEquals(1, log.events.size, "no tick events are required to keep elapsed time current")
            assertEquals(10.minutes, TimerEngine.elapsedAt(log.state(), device.anchor()).counted)
        }

        @Test
        fun `paused time is not counted`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(10.minutes)
            log.pause()
            device.advance(3.hours)
            log.resume()
            device.advance(5.minutes)

            assertEquals(15.minutes, log.elapsed().counted)
        }

        @Test
        fun `pause resume cycles stay exact through doze and backwards wall clock jumps`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(4.minutes)
            log.pause()
            device.advance(10.minutes)
            device.adjustWallClock(-2.hours)
            log.resume()
            device.advance(6.minutes)

            assertEquals(10.minutes, log.elapsed().counted)
        }

        @Test
        fun `stopping freezes the total`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(42.minutes)
            log.stop()
            device.advance(6.hours)

            assertEquals(42.minutes, log.elapsed().counted)
            assertTrue(log.state() is TimerState.Stopped)
        }

        @Test
        fun `deep sleep still counts because elapsedRealtime does`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            // The device dozes for eight hours; elapsedRealtime keeps ticking, so the session does.
            device.advance(8.hours)

            assertEquals(8.hours, log.elapsed().counted)
        }

        @Test
        fun `focus break transitions record break time without counting it`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(25.minutes)
            log.startBreak()
            device.advance(5.minutes)
            log.resumeFocus()
            device.advance(10.minutes)

            assertEquals(
                listOf(
                    SessionEventType.STARTED,
                    SessionEventType.BREAK_STARTED,
                    SessionEventType.FOCUS_RESUMED,
                ),
                log.events.map { it.type },
            )
            assertEquals(35.minutes, log.elapsed().counted)
        }

        @Test
        fun `confirming activity records a heartbeat without restarting the interval`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(2.hours)
            log.confirmActivity()
            device.advance(30.minutes)

            val state = assertIs<TimerState.Running>(log.state())
            assertEquals(log.events.last().anchor, state.lastConfirmedAt)
            assertEquals(2.hours + 30.minutes, log.elapsed().counted)
        }
    }

    @Nested
    @DisplayName("surviving process death")
    inner class ProcessDeath {
        @Test
        fun `replaying the log reproduces the state exactly`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(12.minutes)
            log.pause()
            device.advance(1.minutes)
            log.resume()
            device.advance(8.minutes)

            // Android kills the process here. Nothing is in memory any more; only the log survives.
            val recovered = TimerEngine.fold(log.events.shuffled())

            assertEquals(log.state(), recovered)
            assertEquals(20.minutes, TimerEngine.elapsedAt(recovered, device.anchor()).counted)
        }

        @Test
        fun `ordering comes from the sequence, not from timestamps`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(5.minutes)
            // A clock correction between two events makes the wall-clock timestamps go backwards.
            device.adjustWallClock(-2.hours)
            log.pause()

            val replayed = TimerEngine.fold(log.events.sortedBy { it.anchor.wallClock })

            assertEquals(5.minutes, TimerEngine.elapsedAt(replayed, device.anchor()).counted)
        }
    }

    @Nested
    @DisplayName("ignoring clock changes")
    inner class ClockChanges {
        @Test
        fun `moving the clock forward does not invent study time`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(10.minutes)
            device.adjustWallClock(5.hours)
            device.advance(10.minutes)

            assertEquals(20.minutes, log.elapsed().counted)
        }

        @Test
        fun `moving the clock backward does not erase study time`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(30.minutes)
            device.adjustWallClock(-3.hours)

            assertEquals(30.minutes, log.elapsed().counted)
        }

        @Test
        fun `skew is reported for diagnostics without affecting the measurement`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(10.minutes)
            device.adjustWallClock(45.minutes)

            assertEquals(45.minutes, TimerEngine.wallClockSkew(log.state(), device.anchor()))
            assertEquals(10.minutes, log.elapsed().counted)
        }

        @Test
        fun `sub-second wall-clock drift stays below the documented same-boot tolerance`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(10.minutes)
            device.adjustWallClock(500.milliseconds)

            assertEquals(500.milliseconds, TimerEngine.wallClockSkew(log.state(), device.anchor()))
            assertTrue(
                TimerEngine.wallClockSkew(log.state(), device.anchor())!! < 1.seconds,
                "same-boot drift tolerance is explicitly sub-second",
            )
            assertEquals(10.minutes, log.elapsed().counted)
        }

        @Test
        fun `timezone and DST changes do not change elapsed time`() {
            val device = FakeDevice(startTimeZone = TimeZone.of("America/New_York"))
            val log = Log(device)

            log.start()
            device.advance(30.minutes)
            device.moveTo(TimeZone.of("Europe/Berlin"))
            device.adjustWallClock(1.hours)
            device.advance(30.minutes)

            assertEquals(1.hours, log.elapsed().counted)
        }
    }

    @Nested
    @DisplayName("surviving reboot")
    inner class Reboot {
        @Test
        fun `a reboot while running produces an unverified gap rather than a guess`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(20.minutes)
            device.reboot(downtime = 90.minutes)

            val recovery = TimerEngine.reconcile(log.state(), "recovery-1", device.anchor())

            val gap = assertIs<TimerReconciliation.RebootGap>(recovery)
            assertEquals(110.minutes, gap.unverifiedGap, "20 minutes running plus 90 minutes off")
            assertEquals(SessionEventType.PAUSED, gap.event.type)
            assertEquals(Duration.ZERO, gap.state.settled, "nothing across the boot boundary is counted")
            assertTrue(gap.state.unverified > Duration.ZERO)
        }

        @Test
        fun `time before the reboot is still counted when it was measured in an earlier interval`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(30.minutes)
            log.pause()
            log.resume()
            device.advance(10.minutes)
            device.reboot(downtime = 2.hours)

            val recovery = TimerEngine.reconcile(log.state(), "recovery-1", device.anchor())

            val gap = assertIs<TimerReconciliation.RebootGap>(recovery)
            assertEquals(30.minutes, gap.state.settled, "the closed interval survives untouched")
            assertEquals(130.minutes, gap.state.unverified, "only the interrupted interval is in doubt")
        }

        @Test
        fun `reconciliation is a no-op when nothing was running`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(15.minutes)
            log.pause()
            device.reboot(downtime = 3.hours)

            val recovery = TimerEngine.reconcile(log.state(), "recovery-1", device.anchor())

            assertIs<TimerReconciliation.Unchanged>(recovery)
            assertEquals(15.minutes, TimerEngine.elapsedAt(recovery.state, device.anchor()).counted)
        }

        @Test
        fun `reconciliation is idempotent`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(20.minutes)
            device.reboot(downtime = 30.minutes)

            val first = TimerEngine.reconcile(log.state(), "recovery-1", device.anchor())
            val second = TimerEngine.reconcile(first.state, "recovery-2", device.anchor())

            assertIs<TimerReconciliation.RebootGap>(first)
            assertIs<TimerReconciliation.Unchanged>(second)
        }

        @Test
        fun `a same boot session exceeding the configured maximum is paused at the cap`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(3.hours)

            val recovery =
                TimerEngine.reconcile(
                    state = log.state(),
                    eventId = "recovery-1",
                    now = device.anchor(),
                    maximumRunningDuration = 2.hours,
                )

            val capped = assertIs<TimerReconciliation.MaximumDurationExceeded>(recovery)
            assertEquals(SessionEventType.PAUSED, capped.event.type)
            assertEquals(2.hours, capped.state.settled)
            assertEquals(Duration.ZERO, capped.state.unverified)
            assertEquals(2.hours, TimerEngine.elapsedAt(capped.state, device.anchor()).counted)
        }

        @Test
        fun `a backwards clock across a reboot never yields negative time`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(20.minutes)
            device.reboot()
            // The device came back up believing it is a day earlier.
            device.adjustWallClock(-24.hours)

            val recovery = TimerEngine.reconcile(log.state(), "recovery-1", device.anchor())

            val gap = assertIs<TimerReconciliation.RebootGap>(recovery)
            assertEquals(Duration.ZERO, gap.unverifiedGap)
        }

        @Test
        fun `folding a log written across a reboot never counts the gap`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(20.minutes)
            device.reboot(downtime = 45.minutes)
            log.pause()

            val state = TimerEngine.fold(log.events)

            assertEquals(Duration.ZERO, TimerEngine.elapsedAt(state, device.anchor()).counted)
        }
    }

    @Nested
    @DisplayName("honesty about unverified time")
    inner class Honesty {
        @Test
        fun `unverified time is never reported as counted`() {
            val elapsed = SessionElapsed(counted = 30.minutes, unverified = 90.minutes)

            assertEquals(30.minutes, elapsed.counted)
            assertEquals(2.hours, elapsed.optimisticTotal)
            assertTrue(elapsed.hasUnverifiedTime)
        }
    }

    @Nested
    @DisplayName("rejecting illegal commands")
    inner class IllegalCommands {
        @Test
        fun `cannot pause when idle`() {
            assertRejected(TimerState.Idle, TimerCommand.Pause, TimerRejection.NO_ACTIVE_SESSION)
        }

        @Test
        fun `cannot resume when running`() {
            val device = FakeDevice()
            val log = Log(device)
            log.start()

            assertRejected(log.state(), TimerCommand.Resume, TimerRejection.NOT_PAUSED)
        }

        @Test
        fun `cannot pause when already paused`() {
            val device = FakeDevice()
            val log = Log(device)
            log.start()
            log.pause()

            assertRejected(log.state(), TimerCommand.Pause, TimerRejection.NOT_RUNNING)
        }

        @Test
        fun `cannot start a second session while one is active`() {
            val device = FakeDevice()
            val log = Log(device)
            log.start()

            assertRejected(
                log.state(),
                TimerCommand.Start("another"),
                TimerRejection.SESSION_ALREADY_ACTIVE,
            )
        }

        @Test
        fun `cannot stop a stopped session`() {
            val device = FakeDevice()
            val log = Log(device)
            log.start()
            log.stop()

            assertRejected(log.state(), TimerCommand.Stop, TimerRejection.SESSION_ALREADY_STOPPED)
        }

        @Test
        fun `a new session may start once the previous one stopped`() {
            val device = FakeDevice()
            val log = Log(device)
            log.start()
            device.advance(5.minutes)
            log.stop()

            val result = TimerEngine.execute(log.state(), TimerCommand.Start("next"), "e", device.anchor())

            val accepted = assertIs<TimerCommandResult.Accepted>(result)
            assertEquals(0L, accepted.event.sequence, "a new session starts a fresh log")
            assertEquals(Duration.ZERO, TimerEngine.elapsedAt(accepted.state, device.anchor()).counted)
        }
    }

    @Nested
    @DisplayName("folding edge cases")
    inner class Folding {
        @Test
        fun `an empty log is idle`() {
            assertEquals(TimerState.Idle, TimerEngine.fold(emptyList()))
        }

        @Test
        fun `a zero-length session reports zero, not an error`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            log.stop()

            assertEquals(Duration.ZERO, log.elapsed().counted)
        }

        @Test
        fun `duplicate resume events do not restart the interval`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(10.minutes)
            // A corrupted log with a stray RESUMED while an interval is already open.
            log.append(SessionEventType.RESUMED)
            device.advance(10.minutes)
            log.pause()

            assertEquals(20.minutes, log.elapsed().counted)
        }

        @Test
        fun `duplicate pause events do not count paused time twice`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(10.minutes)
            log.pause()
            device.advance(5.minutes)
            log.append(SessionEventType.PAUSED)

            assertEquals(10.minutes, log.elapsed().counted)
        }

        @Test
        fun `duplicate stop events do not reopen or extend a stopped session`() {
            val device = FakeDevice()
            val log = Log(device)

            log.start()
            device.advance(10.minutes)
            log.stop()
            device.advance(5.minutes)
            log.append(SessionEventType.STOPPED)

            assertEquals(10.minutes, log.elapsed().counted)
            assertTrue(log.state() is TimerState.Stopped)
        }
    }

    /** Minimal in-memory stand-in for the event store, so the tests read like user journeys. */
    private class Log(
        private val device: FakeDevice,
    ) {
        val events = mutableListOf<SessionEvent>()
        private var nextId = 0

        fun state(): TimerState = TimerEngine.fold(events)

        fun elapsed(): SessionElapsed = TimerEngine.elapsedAt(state(), device.anchor())

        fun start(sessionId: String = "session-1") = execute(TimerCommand.Start(sessionId))

        fun pause() = execute(TimerCommand.Pause)

        fun resume() = execute(TimerCommand.Resume)

        fun startBreak() = execute(TimerCommand.StartBreak)

        fun resumeFocus() = execute(TimerCommand.ResumeFocus)

        fun confirmActivity() = execute(TimerCommand.ConfirmActivity)

        fun stop() = execute(TimerCommand.Stop)

        /** Appends an event directly, bypassing validation, to simulate a corrupted log. */
        fun append(type: SessionEventType) {
            events +=
                SessionEvent(
                    id = "raw-${nextId++}",
                    sessionId = events.first().sessionId,
                    type = type,
                    anchor = device.anchor(),
                    sequence = events.last().sequence + 1,
                )
        }

        private fun execute(command: TimerCommand) {
            val result = TimerEngine.execute(state(), command, "event-${nextId++}", device.anchor())
            val accepted =
                result as? TimerCommandResult.Accepted
                    ?: error("command $command was rejected: ${(result as TimerCommandResult.Rejected).reason}")
            events += accepted.event
        }
    }

    private companion object {
        inline fun <reified T> assertIs(value: Any?): T {
            assertTrue(value is T) { "expected ${T::class.simpleName} but was ${value?.let { it::class.simpleName }}" }
            return value as T
        }

        fun assertRejected(
            state: TimerState,
            command: TimerCommand,
            expected: TimerRejection,
        ) {
            val device = FakeDevice()
            val result = TimerEngine.execute(state, command, "event", device.anchor())
            val rejected = assertIs<TimerCommandResult.Rejected>(result)
            assertEquals(expected, rejected.reason)
        }
    }
}
