package dev.studyflow.core.domain.session

import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.domain.timer.TimerCommandResult
import dev.studyflow.core.domain.timer.TimerEngine
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.testing.data.testSessionEvent
import dev.studyflow.core.testing.time.FakeDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The reduction is the contract between the log and everything that reads it: if it is wrong, a
 * crash mid-write turns into a wrong number rather than a recoverable one.
 */
@DisplayName("SessionReducer")
class SessionReducerTest {
    @Nested
    @DisplayName("projecting")
    inner class Projecting {
        @Test
        fun `a session does not exist until it has been started`() {
            assertNull(SessionReducer.reduce(descriptor, events = emptyList()))
        }

        @Test
        fun `a running session reports its start and only settled time`() {
            val device = FakeDevice()
            val log = Log(device)
            log.append(TimerCommand.Start(SESSION_ID))
            device.advance(10.minutes)
            log.append(TimerCommand.Pause)
            device.advance(5.minutes)
            log.append(TimerCommand.Resume)
            device.advance(7.minutes)

            val session = requireNotNull(SessionReducer.reduce(descriptor, log.events))

            assertEquals(SessionStatus.RUNNING, session.status)
            assertEquals(START_WALL_CLOCK, session.startedAt)
            assertNull(session.endedAt)
            // The open interval is deliberately absent: its length depends on when you ask.
            assertEquals(10.minutes, session.elapsed.counted)
            assertEquals(Duration.ZERO, session.elapsed.unverified)
        }

        @Test
        fun `a stopped session freezes its total and records when it ended`() {
            val device = FakeDevice()
            val log = Log(device)
            log.append(TimerCommand.Start(SESSION_ID))
            device.advance(25.minutes)
            log.append(TimerCommand.Stop)

            val session = requireNotNull(SessionReducer.reduce(descriptor, log.events))

            assertEquals(SessionStatus.STOPPED, session.status)
            assertEquals(START_WALL_CLOCK + 25.minutes, session.endedAt)
            assertEquals(25.minutes, session.elapsed.counted)
        }

        @Test
        fun `a reboot mid-session is projected as unverified time, not as study time`() {
            val device = FakeDevice()
            val log = Log(device)
            log.append(TimerCommand.Start(SESSION_ID))
            device.advance(20.minutes)
            log.append(TimerCommand.Pause)
            log.append(TimerCommand.Resume)
            device.reboot(downtime = 3.minutes)
            device.advance(1.minutes)
            log.append(TimerCommand.Stop)

            val session = requireNotNull(SessionReducer.reduce(descriptor, log.events))

            assertEquals(20.minutes, session.elapsed.counted)
            assertEquals(4.minutes, session.elapsed.unverified)
        }

        @Test
        fun `the projection is a function of the log, not of the order it is read in`() {
            val device = FakeDevice()
            val log = Log(device)
            log.append(TimerCommand.Start(SESSION_ID))
            device.advance(2.minutes)
            log.append(TimerCommand.Pause)
            device.advance(2.minutes)
            log.append(TimerCommand.Resume)

            assertEquals(
                SessionReducer.reduce(descriptor, log.events),
                SessionReducer.reduce(descriptor, log.events.shuffled()),
            )
        }

        @Test
        fun `events from another session are refused rather than silently attributed`() {
            val foreign = testSessionEvent(sessionId = "someone-else")

            assertThrows<IllegalArgumentException> { SessionReducer.reduce(descriptor, listOf(foreign)) }
        }
    }

    @Nested
    @DisplayName("transitions")
    inner class Transitions {
        /**
         * Every command sequence up to four deep is replayed against the engine and the reducer.
         *
         * Enumerating them is the only way to be sure that no path through the state machine —
         * including the illegal ones a double tap or a stale notification button produces — leaves
         * the log in a state the projection cannot describe.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.studyflow.core.domain.session.SessionReducerTest#commandSequences")
        fun `legal transitions are accepted and illegal ones change nothing`(sequence: List<TimerCommand>) {
            val device = FakeDevice()
            val log = Log(device)
            var expected: SessionStatus? = null

            sequence.forEach { command ->
                val before = log.events.toList()
                val allowed = command.isLegalIn(expected)

                val accepted = log.append(command)

                assertEquals(allowed, accepted, "$command in state $expected")
                if (accepted) {
                    expected = command.appliedTo(expected)
                } else {
                    assertEquals(before, log.events, "a rejected $command must not write anything")
                }
                device.advance(1.minutes)
            }

            val session = SessionReducer.reduce(log.descriptor, log.events)
            assertEquals(expected, session?.status, "sequence $sequence")
            assertTrue(session == null || session.isActive == (expected != SessionStatus.STOPPED))
        }
    }

    /** Replays commands the way the repository does: fold the log, ask the engine, append. */
    private class Log(
        private val device: FakeDevice,
    ) {
        private val logs = linkedMapOf<String, MutableList<SessionEvent>>()
        private var currentSessionId: String = SESSION_ID

        val events: List<SessionEvent> get() = logs[currentSessionId].orEmpty()

        val descriptor: SessionDescriptor get() = SessionDescriptor(id = currentSessionId, deviceId = DEVICE_ID)

        /** @return true when the command was legal and an event was appended. */
        fun append(command: TimerCommand): Boolean {
            val state = TimerEngine.fold(events)
            // A start always opens a fresh log, exactly as the repository allocates a new id.
            val issued =
                if (command is TimerCommand.Start) command.copy(sessionId = "session-${logs.size + 1}") else command
            val outcome = TimerEngine.execute(state, issued, "event-${logs.values.sumOf { it.size }}", device.anchor())
            if (outcome !is TimerCommandResult.Accepted) return false
            if (issued is TimerCommand.Start) currentSessionId = issued.sessionId
            logs.getOrPut(currentSessionId) { mutableListOf() }.add(outcome.event)
            return true
        }
    }

    private companion object {
        const val SESSION_ID = "session-1"
        const val DEVICE_ID = "device-1"
        val START_WALL_CLOCK = FakeDevice().anchor().wallClock
        val descriptor = SessionDescriptor(id = SESSION_ID, deviceId = DEVICE_ID)

        /** The reference state machine, written independently of [TimerEngine]. */
        fun TimerCommand.isLegalIn(status: SessionStatus?): Boolean =
            when (this) {
                is TimerCommand.Start -> status == null || status == SessionStatus.STOPPED
                TimerCommand.Pause -> status == SessionStatus.RUNNING
                TimerCommand.Resume -> status == SessionStatus.PAUSED
                TimerCommand.Stop -> status == SessionStatus.RUNNING || status == SessionStatus.PAUSED
            }

        fun TimerCommand.appliedTo(status: SessionStatus?): SessionStatus =
            when (this) {
                is TimerCommand.Start, TimerCommand.Resume -> SessionStatus.RUNNING
                TimerCommand.Pause -> SessionStatus.PAUSED
                TimerCommand.Stop -> SessionStatus.STOPPED
            }.also { check(isLegalIn(status)) { "$this is not legal in $status" } }

        @JvmStatic
        fun commandSequences(): List<List<TimerCommand>> {
            val alphabet =
                listOf(
                    TimerCommand.Start(SESSION_ID),
                    TimerCommand.Pause,
                    TimerCommand.Resume,
                    TimerCommand.Stop,
                )
            var sequences = alphabet.map(::listOf)
            val all = sequences.toMutableList()
            repeat(SEQUENCE_DEPTH - 1) {
                sequences = sequences.flatMap { prefix -> alphabet.map { prefix + it } }
                all += sequences
            }
            return all
        }

        /** Four deep visits every state and every illegal edge out of it, including re-starting. */
        const val SEQUENCE_DEPTH = 4
    }
}
