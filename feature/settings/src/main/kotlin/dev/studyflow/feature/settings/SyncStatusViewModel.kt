package dev.studyflow.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.domain.sync.SyncScheduler
import dev.studyflow.core.domain.sync.SyncStatus
import dev.studyflow.core.domain.sync.SyncStatusRepository
import dev.studyflow.core.domain.sync.SyncTrigger
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Instant

/**
 * What the settings screen says about sync (issue #55).
 *
 * Sync is invisible by design, which is exactly why it needs a surface: without one, a device that
 * has been failing to reach the server for a week looks identical to one that is perfectly in
 * step. The three facts here — how much is waiting, when it last worked, what went wrong last —
 * are the minimum needed to tell those two apart.
 */
public data class SyncStatusUiState(
    val status: SyncStatus = SyncStatus(),
) : UiState {
    val pendingCount: Int get() = status.pendingCount
    val lastSuccessAt: Instant? get() = status.lastSuccessAt
    val lastError: String? get() = status.lastError

    /** True when nothing is waiting to be sent and the last attempt did not fail. */
    val isUpToDate: Boolean get() = status.isUpToDate
}

public sealed interface SyncStatusUiEvent : UiEvent {
    /** The user tapped "Sync now". */
    public data object SyncNow : SyncStatusUiEvent
}

/** No effects today; declared so the screen keeps the same MVI shape as its neighbours. */
public sealed interface SyncStatusUiEffect : UiEffect

@HiltViewModel
public class SyncStatusViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        statusRepository: SyncStatusRepository,
        private val syncScheduler: SyncScheduler,
    ) : MviViewModel<SyncStatusUiEvent, SyncStatusUiEffect>(savedStateHandle) {
        public val state: StateFlow<SyncStatusUiState> =
            statusRepository
                .observeStatus()
                // The state is read straight from the store rather than mirrored in a local flag:
                // the run happens in a worker, possibly in another process lifetime, so anything
                // this ViewModel set by hand could outlive the run that finished it.
                .map(::SyncStatusUiState)
                .stateInViewModel(SyncStatusUiState())

        override fun onEvent(event: SyncStatusUiEvent) {
            when (event) {
                SyncStatusUiEvent.SyncNow -> {
                    viewModelScope.launch {
                        syncScheduler.requestSync(SyncTrigger.MANUAL)
                    }
                }
            }
        }
    }
