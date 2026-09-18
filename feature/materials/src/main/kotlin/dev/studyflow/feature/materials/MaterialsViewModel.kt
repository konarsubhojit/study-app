package dev.studyflow.feature.materials

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.domain.materials.ImportOutcome
import dev.studyflow.core.domain.materials.MaterialImporter
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.domain.materials.MaterialUploadCoordinator
import dev.studyflow.core.domain.materials.ShareImportInbox
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailLoader
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration

/** The materials catalogue screen: the current library, plus the outcome of any recent import. */
public data class MaterialsUiState(
    val catalog: List<Material> = emptyList(),
    val loaded: Boolean = false,
    val isImporting: Boolean = false,
    val results: List<MaterialImportResult> = emptyList(),
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

    /** A catalogue row, or a duplicate result's "view existing" action, was selected. */
    public data class ViewExisting(
        val materialId: String,
    ) : MaterialsUiEvent

    /** The user asked to try a [SyncState.Failed] upload again. */
    public data class RetryUpload(
        val materialId: String,
    ) : MaterialsUiEvent
}

public sealed interface MaterialsUiEffect : UiEffect {
    /**
     * Asks the app shell to open the material detail destination for [materialId], the same one a
     * catalogue row and a duplicate import's "view existing" action both resolve to.
     */
    public data class NavigateToMaterial(
        val materialId: String,
    ) : MaterialsUiEffect
}

@HiltViewModel
public class MaterialsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: MaterialRepository,
        private val importer: MaterialImporter,
        private val shareImportInbox: ShareImportInbox,
        private val uploadCoordinator: MaterialUploadCoordinator,
        private val thumbnailLoader: ThumbnailLoader,
        private val dispatcherProvider: DispatcherProvider,
    ) : MviViewModel<MaterialsUiEvent, MaterialsUiEffect>(savedStateHandle) {
        private val results = MutableStateFlow<List<MaterialImportResult>>(emptyList())
        private val importing = MutableStateFlow(false)

        public val state: StateFlow<MaterialsUiState> =
            combine(
                repository.observeAll(),
                results,
                importing,
            ) { catalog, results, importing ->
                MaterialsUiState(
                    catalog = catalog,
                    loaded = true,
                    isImporting = importing,
                    results = results,
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

        /**
         * The grid image for [material], or `null` when this device cannot produce one (issue #42).
         *
         * Pulled per cell rather than pushed through [MaterialsUiState] on purpose: a catalogue of
         * a thousand files would otherwise hold a thousand bitmaps in state, when the grid only
         * ever draws the dozen that are on screen. The caller's coroutine owns the work, so
         * scrolling a cell away cancels both the render and the decode instead of racing them.
         */
        public suspend fun loadThumbnail(material: Material): ImageBitmap? {
            val bytes = thumbnailLoader.load(material) ?: return null
            return withContext(dispatcherProvider.default) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }

        override fun onEvent(event: MaterialsUiEvent) {
            when (event) {
                is MaterialsUiEvent.ImportUris -> {
                    importAll(event.uris)
                }

                MaterialsUiEvent.DismissResults -> {
                    results.value = emptyList()
                }

                is MaterialsUiEvent.ViewExisting -> {
                    emitEffect(MaterialsUiEffect.NavigateToMaterial(event.materialId))
                }

                is MaterialsUiEvent.RetryUpload -> {
                    viewModelScope.launch { uploadCoordinator.retryUpload(event.materialId) }
                }
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
                    viewModelScope.launch { uploadCoordinator.enqueueUpload(material.id) }
                    MaterialImportResult(
                        id = material.id,
                        displayName = displayName,
                        status = MaterialImportResultStatus.Imported(material.id, pageCount, duration),
                    )
                }

                is ImportOutcome.DuplicateFound -> {
                    // The existing material may itself still be uploading (e.g. a retry created a
                    // second local copy before the first one finished); re-enqueuing is then a
                    // no-op or a resume, never a second upload of the same bytes (issue #38).
                    viewModelScope.launch { uploadCoordinator.enqueueUpload(existing.id) }
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
    }
