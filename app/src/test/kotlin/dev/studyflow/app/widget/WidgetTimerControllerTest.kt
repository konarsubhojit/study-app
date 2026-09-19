package dev.studyflow.app.widget

import dev.studyflow.core.domain.session.RecoveredTimerAnchor
import dev.studyflow.core.domain.session.SessionCommandResult
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.model.TimeAnchor
import dev.studyflow.core.testing.time.FakeDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class WidgetTimerControllerTest {
    private val device = FakeDevice()
    private val repository = StateSessionRepository()
    private val controller =
        WidgetTimerController(
            sessionRepository = repository,
            clock = device,
            idGenerator = { "generated-id" },
        )

    @Test
    fun `the widget's play control starts a session when nothing is running`() =
        runTest {
            controller.togglePlayback()

            assertEquals(listOf(TimerCommand.Start(sessionId = "generated-id")), repository.commands)
        }

    @Test
    fun `the widget's play control pauses a running session`() =
        runTest {
            repository.state = running()

            controller.togglePlayback()

            assertEquals(listOf(TimerCommand.Pause), repository.commands)
        }

    @Test
    fun `the widget's play control resumes a paused session`() =
        runTest {
            repository.state = paused()

            controller.togglePlayback()

            assertEquals(listOf(TimerCommand.Resume), repository.commands)
        }

    @Test
    fun `the tile starts a session when there is none`() =
        runTest {
            controller.toggleSession()

            assertEquals(listOf(TimerCommand.Start(sessionId = "generated-id")), repository.commands)
        }

    @Test
    fun `the tile stops whatever session exists, running or paused`() =
        runTest {
            repository.state = running()
            controller.toggleSession()

            repository.state = paused()
            controller.toggleSession()

            assertEquals(listOf(TimerCommand.Stop, TimerCommand.Stop), repository.commands)
        }

    @Test
    fun `the snapshot is folded from the stored log, not from what the caller last saw`() =
        runTest {
            repository.state = paused()

            val snapshot = controller.snapshot()

            assertEquals(TimerWidgetPhase.PAUSED, snapshot.phase)
            assertEquals(12.minutes, snapshot.elapsed)
        }

    private fun running(): TimerState.Running =
        TimerState.Running(
            sessionId = "session",
            settled = Duration.ZERO,
            unverified = Duration.ZERO,
            lastSequence = 1,
            openedAt = device.anchor(),
        )

    private fun paused(): TimerState.Paused =
        TimerState.Paused(
            sessionId = "session",
            settled = 12.minutes,
            unverified = Duration.ZERO,
            lastSequence = 2,
        )
}

/**
 * A [SessionRepository] whose folded state can be set, which is what the widget controller reads
 * before deciding which command a single button stands for.
 */
private class StateSessionRepository : SessionRepository {
    var state: TimerState = TimerState.Idle
    val commands: MutableList<TimerCommand> = mutableListOf()

    override fun observeActiveSession(): Flow<StudySession?> = flowOf(null)

    override fun observeSession(sessionId: String): Flow<StudySession?> = flowOf(null)

    override fun observeSessions(): Flow<List<StudySession>> = flowOf(emptyList())

    override suspend fun activeState(): TimerState = state

    override suspend fun execute(
        command: TimerCommand,
        eventId: String,
        anchor: TimeAnchor,
    ): SessionCommandResult {
        commands += command
        return SessionCommandResult.Unchanged(state)
    }

    override suspend fun reconcile(
        eventId: String,
        now: TimeAnchor,
        recoveredAnchor: RecoveredTimerAnchor?,
    ): SessionCommandResult = SessionCommandResult.Unchanged(state)
}
