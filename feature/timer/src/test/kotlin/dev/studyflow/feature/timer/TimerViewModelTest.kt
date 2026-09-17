package dev.studyflow.feature.timer

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.domain.timer.TimerCommandResult
import dev.studyflow.core.domain.timer.TimerEngine
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import dev.studyflow.core.testing.time.FakeDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class TimerViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    @Test
    fun `initial state is derived from idle timer state`() =
        runTest(mainDispatcher.dispatcher) {
            val device = FakeDevice()
            val viewModel =
                TimerViewModel(
                    savedStateHandle = SavedStateHandle(),
                    timerStates = flowOf(TimerState.Idle),
                    now = device::anchor,
                )

            viewModel.state.test {
                assertEquals(TimerUiState(), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `refresh derives elapsed from timer engine without ticking a counter`() =
        runTest(mainDispatcher.dispatcher) {
            val device = FakeDevice()
            val running = startedState(device)
            val timerStates = MutableStateFlow<TimerState>(running)
            val viewModel =
                TimerViewModel(
                    savedStateHandle = SavedStateHandle(),
                    timerStates = timerStates,
                    now = device::anchor,
                )

            viewModel.state.test {
                assertEquals(TimerUiState(), awaitItem())
                device.advance(10.minutes)
                viewModel.onEvent(TimerUiEvent.Refresh)
                assertEquals(TimerUiState(elapsedSeconds = 600L), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun startedState(device: FakeDevice): TimerState.Running {
        val result =
            TimerEngine.execute(
                state = TimerState.Idle,
                command = TimerCommand.Start(TEST_SESSION_ID),
                eventId = TEST_EVENT_ID,
                anchor = device.anchor(),
            )
        return (result as TimerCommandResult.Accepted).state as TimerState.Running
    }

    private companion object {
        const val TEST_SESSION_ID = "session-1"
        const val TEST_EVENT_ID = "event-1"
    }
}
