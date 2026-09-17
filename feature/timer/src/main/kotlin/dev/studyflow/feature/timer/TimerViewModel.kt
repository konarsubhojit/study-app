package dev.studyflow.feature.timer

import androidx.lifecycle.SavedStateHandle
import dev.studyflow.core.domain.timer.TimerEngine
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.BootId
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlin.time.Duration
import kotlin.time.Instant

public data class TimerUiState(
    val elapsedSeconds: Int = 0,
) : UiState

public sealed interface TimerUiEvent : UiEvent {
    public data object Refresh : TimerUiEvent
}

public sealed interface TimerUiEffect : UiEffect

public class TimerViewModel(
    savedStateHandle: SavedStateHandle,
    initialTimerState: TimerState = TimerState.Idle,
    timerStates: Flow<TimerState> = flowOf(initialTimerState),
    private val now: () -> TimeAnchor = { ZERO_ANCHOR },
) : MviViewModel<TimerUiEvent, TimerUiEffect>(savedStateHandle) {
    private val refreshes =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    public val state: StateFlow<TimerUiState> =
        combine(timerStates, refreshes.onStart { emit(Unit) }) { timerState, _ ->
            timerState.toUiState(now())
        }.stateInViewModel(initialTimerState.toUiState(now()))

    override fun onEvent(event: TimerUiEvent) {
        when (event) {
            TimerUiEvent.Refresh -> refreshes.tryEmit(Unit)
        }
    }

    private companion object {
        val ZERO_ANCHOR = TimeAnchor(Duration.ZERO, Instant.fromEpochMilliseconds(0), BootId("timer-view-model"))

        fun TimerState.toUiState(now: TimeAnchor): TimerUiState =
            TimerUiState(
                elapsedSeconds =
                    TimerEngine
                        .elapsedAt(this, now)
                        .counted
                        .inWholeSeconds
                        .coerceIn(0, Int.MAX_VALUE.toLong())
                        .toInt(),
            )
    }
}
