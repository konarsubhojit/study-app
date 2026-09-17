package dev.studyflow.feature.timer

import androidx.lifecycle.SavedStateHandle
import dev.studyflow.core.domain.timer.TimerEngine
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.TimeAnchor
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart

public data class TimerUiState(
    val elapsedSeconds: Long = 0,
) : UiState

public sealed interface TimerUiEvent : UiEvent {
    public data object Refresh : TimerUiEvent
}

public sealed interface TimerUiEffect : UiEffect

public class TimerViewModel(
    savedStateHandle: SavedStateHandle,
    timerStates: Flow<TimerState>,
    private val now: () -> TimeAnchor,
) : MviViewModel<TimerUiEvent, TimerUiEffect>(savedStateHandle) {
    // Refreshes are rendering invalidations; if several arrive together, one fresh re-read is enough.
    private val refreshes =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    public val state: StateFlow<TimerUiState> =
        combine(timerStates, refreshes.onStart { emit(Unit) }) { timerState, _ ->
            timerState.toUiState(now())
        }.stateInViewModel(TimerUiState())

    override fun onEvent(event: TimerUiEvent) {
        when (event) {
            TimerUiEvent.Refresh -> refreshes.tryEmit(Unit)
        }
    }

    private companion object {
        fun TimerState.toUiState(now: TimeAnchor): TimerUiState =
            TimerUiState(
                elapsedSeconds =
                    TimerEngine
                        .elapsedAt(this, now)
                        .counted
                        .inWholeSeconds,
            )
    }
}
