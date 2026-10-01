package dev.studyflow.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.domain.materials.UploadNetworkSettings
import dev.studyflow.core.domain.sync.SyncScheduler
import dev.studyflow.core.domain.sync.SyncStatus
import dev.studyflow.core.domain.sync.SyncStatusRepository
import dev.studyflow.core.domain.sync.SyncTrigger
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    /** Whether material uploads wait for Wi-Fi; the default, so large files never use mobile data unasked. */
    val wifiOnlyUploads: Boolean = true,
) : UiState {
    val pendingCount: Int get() = status.pendingCount
    val lastSuccessAt: Instant? get() = status.lastSuccessAt
    val lastError: String? get() = status.lastError

    /** True when nothing is waiting to be sent and the last attempt did not fail. */
    val isUpToDate: Boolean get() = status.isUpToDate

    /**
     * True when changes are waiting but no sync pass has ever finished — neither a success nor a
     * failure was recorded.
     *
     * A pass with something queued always records one or the other, so this is the one state in
     * which sync has not *run* rather than run with nothing to do: a signed-out device, or a worker
     * that could never be constructed. Without it both look like an ordinary queue waiting its turn.
     */
    val hasNeverRun: Boolean get() = status.pendingCount > 0 && status.lastAttemptAt == null
}

public sealed interface SyncStatusUiEvent : UiEvent {
    /** The user tapped "Sync now". */
    public data object SyncNow : SyncStatusUiEvent

    /** The user flipped "Upload files on Wi-Fi only". */
    public data class SetWifiOnlyUploads(
        val wifiOnly: Boolean,
    ) : SyncStatusUiEvent
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
        private val uploadNetworkSettings: UploadNetworkSettings,
    ) : MviViewModel<SyncStatusUiEvent, SyncStatusUiEffect>(savedStateHandle) {
        public val state: StateFlow<SyncStatusUiState> =
            // The state is read straight from the store rather than mirrored in a local flag:
            // the run happens in a worker, possibly in another process lifetime, so anything
            // this ViewModel set by hand could outlive the run that finished it.
            combine(statusRepository.observeStatus(), uploadNetworkSettings.wifiOnly, ::SyncStatusUiState)
                .stateInViewModel(SyncStatusUiState())

        override fun onEvent(event: SyncStatusUiEvent) {
            when (event) {
                SyncStatusUiEvent.SyncNow -> {
                    viewModelScope.launch {
                        syncScheduler.requestSync(SyncTrigger.MANUAL)
                    }
                }

                is SyncStatusUiEvent.SetWifiOnlyUploads -> {
                    viewModelScope.launch { uploadNetworkSettings.setWifiOnly(event.wifiOnly) }
                }
            }
        }
    }
