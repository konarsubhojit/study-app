package dev.studyflow.core.ui.mvi

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Base for state-owning UDF ViewModels.
 *
 * Values saved through [stateInSavedState] must be types supported by [SavedStateHandle], such as
 * primitives, strings, arrays, or parcelables. This lets the same key restore after process death.
 */
public abstract class MviViewModel<Event : UiEvent, Effect : UiEffect>(
    protected val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableEffects = MutableSharedFlow<Effect>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    public val effects: SharedFlow<Effect> = mutableEffects.asSharedFlow()

    public abstract fun onEvent(event: Event)

    protected fun emitEffect(effect: Effect) {
        mutableEffects.tryEmit(effect)
    }

    protected fun <T> Flow<T>.stateInSavedState(
        key: String,
        initialValue: T,
    ): StateFlow<T> {
        val restoredValue = savedStateHandle[key] ?: initialValue
        return stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = restoredValue,
        ).also { state ->
            viewModelScope.launch {
                state.collect { savedStateHandle[key] = it }
            }
        }
    }

    protected fun <T> Flow<T>.stateInViewModel(initialValue: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.Eagerly, initialValue)
}
