package dev.studyflow.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.domain.lifecycle.AccountDeletionCoordinator
import dev.studyflow.core.domain.lifecycle.DataExporter
import dev.studyflow.core.domain.lifecycle.DataImporter
import dev.studyflow.core.domain.lifecycle.DeletionConfirmation
import dev.studyflow.core.domain.lifecycle.ExportSummary
import dev.studyflow.core.domain.lifecycle.ImportSummary
import dev.studyflow.core.domain.result.DomainError
import dev.studyflow.core.domain.result.DomainResult
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The long-running thing the screen is in the middle of, if any. */
public enum class DataPrivacyTask {
    EXPORT,
    IMPORT,
    DELETE,
}

/** What the screen has to tell the user about the last thing it did. */
public sealed interface DataPrivacyMessage {
    public data class Exported(
        val records: Int,
        val materialFiles: Int,
        val materialsWithoutBytes: Int,
    ) : DataPrivacyMessage

    public data class Imported(
        val added: Int,
        val updated: Int,
        val unchanged: Int,
        val restoredFiles: Int,
        val rejectedEntries: Int,
    ) : DataPrivacyMessage

    /**
     * @property remaining stores that refused to be cleared. The account is already gone by this
     *   point, so this is an instruction ("clear the app's data"), never an offer to retry.
     */
    public data class AccountDeleted(
        val retentionWindowDays: Int,
        val remaining: List<String>,
    ) : DataPrivacyMessage

    public data class Failed(
        val task: DataPrivacyTask,
        val error: DomainError,
    ) : DataPrivacyMessage
}

public data class DataPrivacyUiState(
    val runningTask: DataPrivacyTask? = null,
    val confirmingDeletion: Boolean = false,
    val message: DataPrivacyMessage? = null,
) : UiState {
    /** One thing at a time: a delete racing an export would export data that is being erased. */
    val isBusy: Boolean get() = runningTask != null
}

public sealed interface DataPrivacyUiEvent : UiEvent {
    /** "Export my data" — the screen still has to ask the user where it goes. */
    public data object ExportRequested : DataPrivacyUiEvent

    /** The document the user created, or `null` when they backed out of the picker. */
    public data class ExportDestinationChosen(
        val uri: String?,
    ) : DataPrivacyUiEvent

    public data object ImportRequested : DataPrivacyUiEvent

    /** The archive the user picked, or `null` when they backed out of the picker. */
    public data class ImportSourceChosen(
        val uri: String?,
    ) : DataPrivacyUiEvent

    public data object DeleteAccountRequested : DataPrivacyUiEvent

    public data object DeleteAccountConfirmed : DataPrivacyUiEvent

    public data object DeleteAccountDismissed : DataPrivacyUiEvent

    public data object MessageDismissed : DataPrivacyUiEvent
}

public sealed interface DataPrivacyUiEffect : UiEffect {
    /** Opens `ACTION_CREATE_DOCUMENT`; only an activity can. */
    public data class CreateArchiveDocument(
        val suggestedName: String,
    ) : DataPrivacyUiEffect

    /** Opens `ACTION_OPEN_DOCUMENT` filtered to archives. */
    public data object OpenArchiveDocument : DataPrivacyUiEffect
}

/**
 * The "Data & privacy" screen: export, restore, delete (issue #78).
 *
 * The three actions share one [DataPrivacyUiState.runningTask] rather than a flag each, because
 * they must not overlap: an import merging rows while an export reads them would write an archive
 * of a state that never existed, and either racing a deletion is worse still.
 *
 * Only [DataPrivacyUiState.confirmingDeletion] survives process death. It is a decision the user
 * is part way through, whereas a finished export's summary describes work that is over, and a
 * `runningTask` restored after the process died would describe a coroutine that no longer exists.
 */
@HiltViewModel
public class DataPrivacyViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val exporter: DataExporter,
        private val importer: DataImporter,
        private val deletionCoordinator: AccountDeletionCoordinator,
        private val archiveNaming: ArchiveNaming,
    ) : MviViewModel<DataPrivacyUiEvent, DataPrivacyUiEffect>(savedStateHandle) {
        private val progress = MutableStateFlow(DataPrivacyUiState())
        private val confirmingDeletionChanges = MutableStateFlow(savedStateHandle[CONFIRMING_DELETION_KEY] ?: false)
        private val confirmingDeletion = confirmingDeletionChanges.stateInSavedState(CONFIRMING_DELETION_KEY, false)

        public val state: StateFlow<DataPrivacyUiState> =
            combine(progress, confirmingDeletion) { current, confirming ->
                current.copy(confirmingDeletion = confirming)
            }.stateInViewModel(progress.value)

        override fun onEvent(event: DataPrivacyUiEvent) {
            when (event) {
                DataPrivacyUiEvent.ExportRequested -> {
                    if (!progress.value.isBusy) {
                        emitEffect(DataPrivacyUiEffect.CreateArchiveDocument(archiveNaming.suggest()))
                    }
                }

                is DataPrivacyUiEvent.ExportDestinationChosen -> {
                    event.uri?.let { uri -> run(DataPrivacyTask.EXPORT) { exporter.export(uri).asExportMessage() } }
                }

                DataPrivacyUiEvent.ImportRequested -> {
                    if (!progress.value.isBusy) emitEffect(DataPrivacyUiEffect.OpenArchiveDocument)
                }

                is DataPrivacyUiEvent.ImportSourceChosen -> {
                    event.uri?.let { uri -> run(DataPrivacyTask.IMPORT) { importer.import(uri).asImportMessage() } }
                }

                DataPrivacyUiEvent.DeleteAccountRequested,
                DataPrivacyUiEvent.DeleteAccountDismissed,
                DataPrivacyUiEvent.DeleteAccountConfirmed,
                -> {
                    onDeletionEvent(event)
                }

                DataPrivacyUiEvent.MessageDismissed -> {
                    progress.value = progress.value.copy(message = null)
                }
            }
        }

        /** The confirmation handshake, kept together so the "only after a yes" rule reads as one rule. */
        private fun onDeletionEvent(event: DataPrivacyUiEvent) {
            confirmingDeletionChanges.value = event == DataPrivacyUiEvent.DeleteAccountRequested
            if (event == DataPrivacyUiEvent.DeleteAccountConfirmed) deleteAccount()
        }

        private fun deleteAccount() =
            run(DataPrivacyTask.DELETE) {
                when (val result = deletionCoordinator.deleteAccount(DeletionConfirmation(acknowledged = true))) {
                    is DomainResult.Success -> {
                        DataPrivacyMessage.AccountDeleted(
                            retentionWindowDays = result.value.receipt.retentionWindowDays,
                            remaining = result.value.failed,
                        )
                    }

                    is DomainResult.Failure -> {
                        DataPrivacyMessage.Failed(DataPrivacyTask.DELETE, result.error)
                    }
                }
            }

        /**
         * Runs one task, refusing to start a second.
         *
         * The guard lives here rather than at each call site so a new action cannot forget it; the
         * events that launch a picker check it too, so the *picker* never opens for an action that
         * would be refused on return.
         */
        private fun run(
            task: DataPrivacyTask,
            block: suspend () -> DataPrivacyMessage,
        ) {
            if (progress.value.isBusy) return
            progress.value = progress.value.copy(runningTask = task, message = null)
            viewModelScope.launch {
                val message = block()
                progress.value = DataPrivacyUiState(runningTask = null, message = message)
            }
        }

        private fun DomainResult<ExportSummary>.asExportMessage() =
            when (this) {
                is DomainResult.Success -> {
                    DataPrivacyMessage.Exported(
                        records = value.records,
                        materialFiles = value.materialFiles,
                        materialsWithoutBytes = value.materialsWithoutBytes,
                    )
                }

                is DomainResult.Failure -> {
                    DataPrivacyMessage.Failed(DataPrivacyTask.EXPORT, error)
                }
            }

        private fun DomainResult<ImportSummary>.asImportMessage() =
            when (this) {
                is DomainResult.Success -> {
                    DataPrivacyMessage.Imported(
                        added = value.added,
                        updated = value.updated,
                        unchanged = value.unchanged,
                        restoredFiles = value.restoredFiles,
                        rejectedEntries = value.rejectedEntries,
                    )
                }

                is DomainResult.Failure -> {
                    DataPrivacyMessage.Failed(DataPrivacyTask.IMPORT, error)
                }
            }

        private companion object {
            const val CONFIRMING_DELETION_KEY = "dataPrivacy.confirmingDeletion"
        }
    }

/**
 * Names the file the export is written to.
 *
 * A port rather than a `LocalDate.now()` call so the suggested name is a fixed string in a test,
 * and so the one place the user sees a date follows the device's calendar rather than UTC.
 */
public fun interface ArchiveNaming {
    public fun suggest(): String
}
