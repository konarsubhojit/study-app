package dev.studyflow.feature.timer

import androidx.lifecycle.SavedStateHandle
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

public data class TimerUiState(
    val elapsedSeconds: Int = 0,
) : UiState

public sealed interface TimerUiEvent : UiEvent {
    public data object Tick : TimerUiEvent

    public data object Reset : TimerUiEvent
}

public sealed interface TimerUiEffect : UiEffect

public class TimerViewModel(
    savedStateHandle: SavedStateHandle,
) : MviViewModel<TimerUiEvent, TimerUiEffect>(savedStateHandle) {
    private val elapsedSecondChanges = MutableStateFlow(savedStateHandle[ELAPSED_SECONDS_KEY] ?: 0)
    private val elapsedSeconds =
        elapsedSecondChanges
            .stateInSavedState(ELAPSED_SECONDS_KEY, 0)

    public val state: StateFlow<TimerUiState> =
        elapsedSeconds
            .map(::TimerUiState)
            .stateInViewModel(TimerUiState(elapsedSeconds.value))

    override fun onEvent(event: TimerUiEvent) {
        when (event) {
            TimerUiEvent.Tick -> elapsedSecondChanges.value += 1
            TimerUiEvent.Reset -> elapsedSecondChanges.value = 0
        }
    }

    private companion object {
        const val ELAPSED_SECONDS_KEY = "timer.elapsedSeconds"
    }
}
