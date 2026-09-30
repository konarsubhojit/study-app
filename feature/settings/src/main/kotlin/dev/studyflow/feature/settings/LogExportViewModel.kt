package dev.studyflow.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.domain.result.DomainError
import dev.studyflow.core.domain.result.DomainResult
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the export card has to tell the user about the last export. */
public sealed interface LogExportMessage {
    public data class Exported(
        val fileName: String,
        val inDownloads: Boolean,
    ) : LogExportMessage

    public data object Empty : LogExportMessage

    public data class Failed(
        val error: DomainError,
    ) : LogExportMessage
}

public data class LogExportUiState(
    val exporting: Boolean = false,
    val message: LogExportMessage? = null,
    /** Set once a file exists, so "Share" appears only when there is something to share. */
    val shareUri: String? = null,
) : UiState

public sealed interface LogExportUiEvent : UiEvent {
    public data object ExportRequested : LogExportUiEvent

    public data object ShareRequested : LogExportUiEvent

    public data object MessageDismissed : LogExportUiEvent
}

public sealed interface LogExportUiEffect : UiEffect {
    /** Offers the written file to another app; only an activity can start the chooser. */
    public data class ShareFile(
        val uri: String,
    ) : LogExportUiEffect
}

/**
 * "Export logs": the user's own copy of what the app has been saying about itself.
 *
 * The export is entirely user-initiated and entirely local — nothing is uploaded — which is what
 * makes it an acceptable answer to "release builds log almost nothing": the detail is kept on the
 * device until its owner decides to hand it over.
 */
@HiltViewModel
public class LogExportViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val exporter: LogExporter,
    ) : MviViewModel<LogExportUiEvent, LogExportUiEffect>(savedStateHandle) {
        private val mutableState = MutableStateFlow(LogExportUiState())
        public val state: StateFlow<LogExportUiState> = mutableState

        override fun onEvent(event: LogExportUiEvent) {
            when (event) {
                LogExportUiEvent.ExportRequested -> export()
                LogExportUiEvent.ShareRequested -> {
                    mutableState.value.shareUri?.let { uri -> emitEffect(LogExportUiEffect.ShareFile(uri)) }
                }

                LogExportUiEvent.MessageDismissed -> {
                    mutableState.value = mutableState.value.copy(message = null)
                }
            }
        }

        private fun export() {
            if (mutableState.value.exporting) return
            mutableState.value = LogExportUiState(exporting = true)
            viewModelScope.launch {
                mutableState.value = exporter.export().asState()
            }
        }

        private fun DomainResult<LogExportOutcome>.asState(): LogExportUiState =
            when (this) {
                is DomainResult.Success -> {
                    when (val outcome = value) {
                        LogExportOutcome.Empty -> LogExportUiState(message = LogExportMessage.Empty)
                        is LogExportOutcome.Exported -> {
                            LogExportUiState(
                                message =
                                    LogExportMessage.Exported(
                                        fileName = outcome.fileName,
                                        inDownloads = outcome.inDownloads,
                                    ),
                                shareUri = outcome.shareUri,
                            )
                        }
                    }
                }

                is DomainResult.Failure -> LogExportUiState(message = LogExportMessage.Failed(error))
            }
    }
