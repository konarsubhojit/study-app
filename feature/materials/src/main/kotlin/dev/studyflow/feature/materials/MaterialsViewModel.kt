package dev.studyflow.feature.materials

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.domain.materials.ImportOutcome
import dev.studyflow.core.domain.materials.MaterialImporter
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.domain.materials.ShareImportInbox
import dev.studyflow.core.model.Material
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration

/** The materials catalogue screen: the current library, plus the outcome of any recent import. */
public data class MaterialsUiState(
    val catalog: List<Material> = emptyList(),
    val loaded: Boolean = false,
    val isImporting: Boolean = false,
    val results: List<MaterialImportResult> = emptyList(),
    val highlightedMaterialId: String? = null,
) : UiState

/** One file's outcome from a picker, document, or share-sheet import. */
public data class MaterialImportResult(
    val id: String,
    val displayName: String,
    val status: MaterialImportResultStatus,
)

/** What happened to one file, in terms the screen can render without seeing [ImportOutcome]. */
public sealed interface MaterialImportResultStatus {
    public data class Imported(
        val materialId: String,
        val pageCount: Int?,
        val duration: Duration?,
    ) : MaterialImportResultStatus

    public data class Duplicate(
        val existingId: String,
        val existingDisplayName: String,
    ) : MaterialImportResultStatus

    public data class Rejected(
        val message: String,
    ) : MaterialImportResultStatus

    public data class Failed(
        val message: String,
    ) : MaterialImportResultStatus
}

public sealed interface MaterialsUiEvent : UiEvent {
    /** URIs from the Photo Picker, `ACTION_OPEN_DOCUMENT`, or a share intent. */
    public data class ImportUris(
        val uris: List<String>,
    ) : MaterialsUiEvent

    /** Clears the results banner once the user has seen it. */
    public data object DismissResults : MaterialsUiEvent

    /** A duplicate result's "view existing" action: highlights the item already in the catalogue. */
    public data class ViewExisting(
        val materialId: String,
    ) : MaterialsUiEvent
}

public sealed interface MaterialsUiEffect : UiEffect

@HiltViewModel
public class MaterialsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: MaterialRepository,
        private val importer: MaterialImporter,
        private val shareImportInbox: ShareImportInbox,
    ) : MviViewModel<MaterialsUiEvent, MaterialsUiEffect>(savedStateHandle) {
        private val results = MutableStateFlow<List<MaterialImportResult>>(emptyList())
        private val importing = MutableStateFlow(false)

        // Which catalogue row to draw attention to survives process death: it is the direct result
        // of a user action ("show me the one I already have"), not state that should reset silently.
        private val highlightChanges = MutableStateFlow<String?>(savedStateHandle[HIGHLIGHT_KEY])
        private val highlighted = highlightChanges.stateInSavedState(HIGHLIGHT_KEY, null)

        public val state: StateFlow<MaterialsUiState> =
            combine(
                repository.observeAll(),
                results,
                importing,
                highlighted,
            ) { catalog, results, importing, highlighted ->
                MaterialsUiState(
                    catalog = catalog,
                    loaded = true,
                    isImporting = importing,
                    results = results,
                    highlightedMaterialId = highlighted,
                )
            }.stateInViewModel(MaterialsUiState())

        init {
            // A share intent can arrive before this view model ever existed; the inbox is how a
            // cold-start or warm `onNewIntent` share hands its URIs to whichever screen collects
            // next, however late that is.
            viewModelScope.launch {
                shareImportInbox.pending.collect { uris ->
                    if (uris.isNotEmpty()) {
                        importAll(uris)
                        shareImportInbox.consume()
                    }
                }
            }
        }

        override fun onEvent(event: MaterialsUiEvent) {
            when (event) {
                is MaterialsUiEvent.ImportUris -> importAll(event.uris)
                MaterialsUiEvent.DismissResults -> results.value = emptyList()
                is MaterialsUiEvent.ViewExisting -> highlightChanges.value = event.materialId
            }
        }

        private fun importAll(uris: List<String>) {
            if (uris.isEmpty()) return
            viewModelScope.launch {
                importing.value = true
                try {
                    // Sequentially rather than in parallel: a shared phone's storage and a metered
                    // connection both make "several hundred-megabyte files at once" the wrong default,
                    // and results still stream into the UI as each one finishes.
                    uris.forEach { uri -> results.value = results.value + importer.import(uri).toResult() }
                } finally {
                    importing.value = false
                }
            }
        }

        private fun ImportOutcome.toResult(): MaterialImportResult =
            when (this) {
                is ImportOutcome.Imported -> {
                    MaterialImportResult(
                        id = material.id,
                        displayName = displayName,
                        status = MaterialImportResultStatus.Imported(material.id, pageCount, duration),
                    )
                }

                is ImportOutcome.DuplicateFound -> {
                    MaterialImportResult(
                        id = UUID.randomUUID().toString(),
                        displayName = displayName,
                        status = MaterialImportResultStatus.Duplicate(existing.id, existing.displayName),
                    )
                }

                is ImportOutcome.Rejected -> {
                    MaterialImportResult(
                        id = UUID.randomUUID().toString(),
                        displayName = displayName,
                        status = MaterialImportResultStatus.Rejected(MaterialsCopy.rejection(reason)),
                    )
                }

                is ImportOutcome.Failed -> {
                    MaterialImportResult(
                        id = UUID.randomUUID().toString(),
                        displayName = displayName,
                        status = MaterialImportResultStatus.Failed(MaterialsCopy.failure(reason)),
                    )
                }
            }

        private companion object {
            const val HIGHLIGHT_KEY = "materials.highlightedMaterialId"
        }
    }
