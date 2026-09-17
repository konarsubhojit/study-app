package dev.studyflow.feature.timer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import dev.studyflow.core.domain.session.SessionCommandResult
import dev.studyflow.core.domain.session.SessionDescriptor
import dev.studyflow.core.domain.session.SessionReducer
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.domain.timer.TimerCommandResult
import dev.studyflow.core.domain.timer.TimerEngine
import dev.studyflow.core.domain.timer.TimerReconciliation
import dev.studyflow.core.domain.timer.TimerRejection
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.model.TimeAnchor
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import dev.studyflow.core.testing.data.FakeSubjectRepository
import dev.studyflow.core.testing.data.testSubject
import dev.studyflow.core.testing.time.FakeDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("TimerViewModel")
class TimerViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val device = FakeDevice()
    private val sessionRepository = InMemorySessionRepository()
    private val subjectRepository = FakeSubjectRepository()

    private val viewModels = mutableListOf<TimerViewModel>()

    @Test
    fun `initial state is idle with no elapsed time`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            assertEquals(TimerPhase.IDLE, viewModel.state.value.phase)
            assertEquals(0L, viewModel.state.value.elapsedSeconds)
        }

    @Test
    fun `starting a session moves the phase to running and carries the chosen subject and note`() =
        runTest(mainDispatcher.dispatcher) {
            subjectRepository.put(testSubject(id = "subject-1"))
            val viewModel = viewModel()

            viewModel.state.test {
                awaitItem() // initial idle state
                viewModel.onEvent(TimerUiEvent.SubjectSelected("subject-1"))
                assertEquals("subject-1", awaitItem().selectedSubjectId)
                viewModel.onEvent(TimerUiEvent.NoteChanged("Chapter 4 exercises"))
                assertEquals("Chapter 4 exercises", awaitItem().note)

                viewModel.onEvent(TimerUiEvent.StartRequested)
                val running = expectMostRecentItem()
                assertEquals(TimerPhase.RUNNING, running.phase)
                assertEquals("subject-1", running.selectedSubjectId)
                assertEquals("Chapter 4 exercises", running.note)
                cancelAndIgnoreRemainingEvents()
            }
            stopTickers()
        }

    @Test
    fun `pause then resume round-trips back to running without losing settled time`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()
            viewModel.onEvent(TimerUiEvent.StartRequested)

            viewModel.state.test {
                awaitItem() // running, freshly started

                device.advance(5.minutes)
                viewModel.onEvent(TimerUiEvent.PauseRequested)
                val paused = expectMostRecentItem()
                assertEquals(TimerPhase.PAUSED, paused.phase)
                assertEquals(300L, paused.elapsedSeconds)

                device.advance(10.minutes) // must not count while paused
                viewModel.onEvent(TimerUiEvent.ResumeRequested)
                val resumed = expectMostRecentItem()
                assertEquals(TimerPhase.RUNNING, resumed.phase)
                assertEquals(300L, resumed.elapsedSeconds)
                cancelAndIgnoreRemainingEvents()
            }
            stopTickers()
        }

    @Test
    fun `stopping a session returns to idle`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()
            viewModel.onEvent(TimerUiEvent.StartRequested)

            viewModel.state.test {
                awaitItem()
                device.advance(2.minutes)
                viewModel.onEvent(TimerUiEvent.StopRequested)
                val stopped = expectMostRecentItem()
                assertEquals(TimerPhase.IDLE, stopped.phase)
                cancelAndIgnoreRemainingEvents()
            }
            stopTickers()
        }

    @Test
    fun `the running elapsed display ticks forward once a second without a manual refresh`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()
            viewModel.onEvent(TimerUiEvent.StartRequested)

            viewModel.state.test {
                awaitItem()
                device.advance(1.seconds)
                advanceTimeBy(1_100)
                assertEquals(1L, expectMostRecentItem().elapsedSeconds)
                cancelAndIgnoreRemainingEvents()
            }
            stopTickers()
        }

    @Test
    fun `a command the engine refuses is surfaced as an effect and leaves state untouched`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.effects.test {
                viewModel.onEvent(TimerUiEvent.PauseRequested) // nothing is running yet
                assertEquals(TimerRejection.NO_ACTIVE_SESSION, (awaitItem() as TimerUiEffect.CommandRejected).reason)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(TimerPhase.IDLE, viewModel.state.value.phase)
            stopTickers()
        }

    @Test
    fun `a reboot while running is recovered as paused with unverified time, not lost or silently counted`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()
            viewModel.onEvent(TimerUiEvent.StartRequested)
            device.advance(5.minutes)
            device.reboot(downtime = 20.minutes)

            val recovered = viewModel().state
            recovered.test {
                val state = expectMostRecentItem()
                assertEquals(TimerPhase.PAUSED, state.phase)
                assertTrue(state.hasUnverifiedTime)
                assertEquals(300L, state.elapsedSeconds) // settled time is untouched
                cancelAndIgnoreRemainingEvents()
            }
            stopTickers()
        }

    @Test
    fun `keep-screen-on toggles independently of the timer's own state`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()
            assertFalse(viewModel.state.value.keepScreenOn)

            viewModel.state.test {
                awaitItem() // initial state
                viewModel.onEvent(TimerUiEvent.KeepScreenOnChanged(true))
                assertTrue(awaitItem().keepScreenOn)
                cancelAndIgnoreRemainingEvents()
            }
            stopTickers()
        }

    private fun viewModel(): TimerViewModel =
        TimerViewModel(
            savedStateHandle = SavedStateHandle(),
            sessionRepository = sessionRepository,
            subjectRepository = subjectRepository,
            anchoredClock = device,
        ).also { viewModels += it }

    // A viewModel's own state is collected eagerly (see stateInViewModel) and, while running, that
    // collection includes a once-a-second ticker that would otherwise keep rescheduling itself
    // forever and stop `runTest` from ever reaching idle.
    private fun stopTickers() {
        viewModels.forEach { it.viewModelScope.cancel() }
    }
}

/**
 * A real (not command-recording) [SessionRepository], backed by an in-memory event log rather than
 * Room, so this test exercises actual [TimerEngine] rules — pause/resume/stop and reboot recovery
 * — the same way [dev.studyflow.core.database.session.OfflineFirstSessionRepository] does.
 */
private class InMemorySessionRepository : SessionRepository {
    private val logs = mutableMapOf<String, MutableList<SessionEvent>>()
    private val descriptors = mutableMapOf<String, SessionDescriptor>()
    private val sessionsFlow = MutableStateFlow<Map<String, StudySession>>(emptyMap())

    override fun observeActiveSession(): Flow<StudySession?> =
        sessionsFlow.map { sessions -> sessions.values.firstOrNull { it.isActive } }

    override fun observeSession(sessionId: String): Flow<StudySession?> = sessionsFlow.map { it[sessionId] }

    override suspend fun activeState(): TimerState {
        val active = activeSessionId() ?: return TimerState.Idle
        return TimerEngine.fold(logs.getValue(active))
    }

    override suspend fun execute(
        command: TimerCommand,
        eventId: String,
        anchor: TimeAnchor,
    ): SessionCommandResult {
        val activeId = activeSessionId()
        val state = activeId?.let { TimerEngine.fold(logs.getValue(it)) } ?: TimerState.Idle
        return when (val outcome = TimerEngine.execute(state, command, eventId, anchor)) {
            is TimerCommandResult.Rejected -> SessionCommandResult.Rejected(outcome.reason)
            is TimerCommandResult.Accepted -> commit(command.descriptorFor(activeId), outcome.event, outcome.state)
        }
    }

    override suspend fun reconcile(
        eventId: String,
        now: TimeAnchor,
    ): SessionCommandResult {
        val activeId = activeSessionId() ?: return SessionCommandResult.Unchanged(TimerState.Idle)
        val state = TimerEngine.fold(logs.getValue(activeId))
        return when (val outcome = TimerEngine.reconcile(state, eventId, now)) {
            is TimerReconciliation.Unchanged -> SessionCommandResult.Unchanged(outcome.state)
            is TimerReconciliation.Adjustment -> commit(descriptors.getValue(activeId), outcome.event, outcome.state)
        }
    }

    private fun commit(
        descriptor: SessionDescriptor,
        event: SessionEvent,
        state: TimerState.Active,
    ): SessionCommandResult.Applied {
        descriptors[descriptor.id] = descriptor
        val log = logs.getOrPut(descriptor.id) { mutableListOf() }
        log += event
        val session = requireNotNull(SessionReducer.reduce(descriptor, log))
        sessionsFlow.value = sessionsFlow.value + (session.id to session)
        return SessionCommandResult.Applied(session, state)
    }

    private fun activeSessionId(): String? =
        sessionsFlow.value.values
            .firstOrNull { it.isActive }
            ?.id

    private fun TimerCommand.descriptorFor(activeId: String?): SessionDescriptor =
        when (this) {
            is TimerCommand.Start -> {
                SessionDescriptor(
                    id = sessionId,
                    deviceId = "device-1",
                    subjectId = subjectId,
                    note = note,
                )
            }

            else -> {
                descriptors.getValue(requireNotNull(activeId) { "$this has no session to apply to" })
            }
        }
}
