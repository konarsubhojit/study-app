package dev.studyflow.feature.timer

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class TimerViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    @Test
    fun `tick refresh does not mutate elapsed state`() =
        runTest(mainDispatcher.dispatcher) {
            val savedState = SavedStateHandle(mapOf("timer.elapsedSeconds" to 600))
            val viewModel = TimerViewModel(savedState)

            viewModel.state.test {
                assertEquals(TimerUiState(), awaitItem())
                viewModel.onEvent(TimerUiEvent.Tick)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
}
