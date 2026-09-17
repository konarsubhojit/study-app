package dev.studyflow.feature.timer

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class TimerViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    @Test
    fun `tick updates state and restores it from saved state`() =
        runTest(mainDispatcher.dispatcher) {
            val savedState = SavedStateHandle()
            val viewModel = TimerViewModel(savedState)

            viewModel.state.test {
                assertEquals(TimerUiState(), awaitItem())
                viewModel.onEvent(TimerUiEvent.Tick)
                assertEquals(TimerUiState(elapsedSeconds = 1), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            advanceUntilIdle()

            TimerViewModel(savedState).state.test {
                assertEquals(TimerUiState(elapsedSeconds = 1), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
