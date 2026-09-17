package dev.studyflow.feature.timer

import androidx.lifecycle.SavedStateHandle
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

public data class TimerUiState(
    val elapsedSeconds: Int = 0,
) : UiState

public sealed interface TimerUiEffect : UiEffect

public class TimerViewModel(
    savedStateHandle: SavedStateHandle,
) : MviViewModel<Nothing, TimerUiEffect>(savedStateHandle) {
    public val state: StateFlow<TimerUiState> =
        flowOf(TimerUiState())
            .stateInViewModel(TimerUiState())

    override fun onEvent(event: Nothing): Unit = event
}
