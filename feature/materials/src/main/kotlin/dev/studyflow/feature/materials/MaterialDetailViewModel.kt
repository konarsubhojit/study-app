package dev.studyflow.feature.materials

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.domain.materials.ArchiveEntryVerdict
import dev.studyflow.core.domain.materials.ArchiveExtractionResult
import dev.studyflow.core.domain.materials.ArchiveListing
import dev.studyflow.core.domain.materials.ArchiveRejection
import dev.studyflow.core.domain.materials.ArchiveVerdict
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.MaterialKind
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import dev.studyflow.feature.materials.data.ArchiveEntryExtractor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Everything the material detail screen renders for one catalogue entry (issue #37).
 *
 * [loading] is true only before the first snapshot for the requested id has arrived; a material
 * that genuinely does not exist (deleted, or a stale "view existing" link) settles into [material]
 * being `null` with [loading] false, which is what lets the screen tell "still loading" apart from
 * "not found".
 */
public data class MaterialDetailUiState(
    val loading: Boolean = true,
    val material: Material? = null,
    val previewSource: MaterialPreviewSource? = null,
    val previewSourceLoading: Boolean = false,
    val previewSourceMessage: String? = null,
    val archivePreview: ArchivePreviewUiState? = null,
) : UiState {
    public val notFound: Boolean
        get() = !loading && material == null
}

public sealed interface MaterialPreviewSource {
    public val uri: String

    public data class Local(
        override val uri: String,
    ) : MaterialPreviewSource

    public data class Remote(
        override val uri: String,
    ) : MaterialPreviewSource
}

private data class MaterialPreviewSourceState(
    val source: MaterialPreviewSource? = null,
    val loading: Boolean = false,
    val message: String? = null,
)

/**
 * What the archive branch of the preview shows for one [MaterialKind.ARCHIVE] material (issue #40).
 *
 * [entries] only ever holds what [ArchiveVerdict.accepted] already cleared; anything refused is
 * summarised in [message] instead, by name never shown, so the screen can explain the refusal
 * ([ArchiveRejection]) without listing a row that must not be opened.
 */
public data class ArchivePreviewUiState(
    val loading: Boolean = false,
    val entries: List<ArchiveEntryUiState> = emptyList(),
    val message: String? = null,
)

/** One safe-to-extract archive entry and the state of extracting it. */
public data class ArchiveEntryUiState(
    val entry: ArchiveEntryVerdict.Accepted,
    val extraction: ArchiveExtractionUiState = ArchiveExtractionUiState.Idle,
) {
    public val path: String get() = entry.path
    public val name: String get() = entry.name
    public val sizeBytes: Long get() = entry.declaredSize
}

/** Per-entry extraction progress, driven by [MaterialDetailUiEvent.ExtractArchiveEntry]. */
public sealed interface ArchiveExtractionUiState {
    public data object Idle : ArchiveExtractionUiState

    public data object Extracting : ArchiveExtractionUiState

    /** [localPath] is under the app's private storage; wrap it with `FileProvider` before sharing. */
    public data class Done(
        val localPath: String,
    ) : ArchiveExtractionUiState

    public data class Failed(
        val message: String,
    ) : ArchiveExtractionUiState
}

public sealed interface MaterialDetailUiEvent : UiEvent {
    /** Requests the material with [materialId]; sent once, when the screen is first shown. */
    public data class Load(
        val materialId: String,
    ) : MaterialDetailUiEvent

    public data class PdfPageChanged(
        val pageIndex: Int,
    ) : MaterialDetailUiEvent

    public data class PlaybackChanged(
        val positionMillis: Long,
        val playbackSpeed: Float,
    ) : MaterialDetailUiEvent

    public data object DeleteMaterial : MaterialDetailUiEvent

    /** Extracts the entry at [entryPath] (an [ArchiveEntryVerdict.Accepted.path]) on demand. */
    public data class ExtractArchiveEntry(
        val entryPath: String,
    ) : MaterialDetailUiEvent

    /** Stops an in-flight extraction for [entryPath]; a no-op once it has finished. */
    public data class CancelArchiveExtraction(
        val entryPath: String,
    ) : MaterialDetailUiEvent
}

public sealed interface MaterialDetailUiEffect : UiEffect {
    public data object CloseMaterial : MaterialDetailUiEffect
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
public class MaterialDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: MaterialRepository,
        private val objectStore: ObjectStore,
        private val archiveEntryExtractor: ArchiveEntryExtractor,
    ) : MviViewModel<MaterialDetailUiEvent, MaterialDetailUiEffect>(savedStateHandle) {
        private val materialId = MutableStateFlow(savedStateHandle.get<String>(MATERIAL_ID_KEY))
        private val previewSource = MutableStateFlow(MaterialPreviewSourceState())
        private val archivePreview = MutableStateFlow<ArchivePreviewUiState?>(null)
        private val extractionJobs = mutableMapOf<String, Job>()
        private var sourceJob: Job? = null
        private var currentArchiveFile: File? = null

        public val state: StateFlow<MaterialDetailUiState> =
            combine(
                materialId
                    .flatMapLatest { id ->
                        id
                            ?.let { repository.observeById(it) }
                            ?.map { material -> MaterialDetailUiState(loading = false, material = material) }
                            ?: flowOf(MaterialDetailUiState())
                    },
                previewSource,
                archivePreview,
            ) { state, source, archive ->
                state.copy(
                    previewSource = source.source,
                    previewSourceLoading = source.loading,
                    previewSourceMessage = source.message,
                    archivePreview = archive,
                )
            }.stateInViewModel(MaterialDetailUiState())

        override fun onEvent(event: MaterialDetailUiEvent) {
            when (event) {
                is MaterialDetailUiEvent.Load -> {
                    savedStateHandle[MATERIAL_ID_KEY] = event.materialId
                    materialId.value = event.materialId
                    observePreviewSource(event.materialId)
                }

                is MaterialDetailUiEvent.PdfPageChanged -> {
                    require(event.pageIndex >= 0) { "pageIndex must not be negative" }
                    materialId.value?.let { id ->
                        viewModelScope.launch {
                            repository.updatePreviewState(
                                id = id,
                                pageIndex = event.pageIndex,
                                positionMillis = null,
                                playbackSpeed = null,
                            )
                        }
                    }
                }

                is MaterialDetailUiEvent.PlaybackChanged -> {
                    require(event.positionMillis >= 0) { "positionMillis must not be negative" }
                    require(event.playbackSpeed > 0f) { "playbackSpeed must be positive" }
                    materialId.value?.let { id ->
                        viewModelScope.launch {
                            repository.updatePreviewState(
                                id = id,
                                pageIndex = null,
                                positionMillis = event.positionMillis,
                                playbackSpeed = event.playbackSpeed,
                            )
                        }
                    }
                }

                MaterialDetailUiEvent.DeleteMaterial -> {
                    materialId.value?.let { id ->
                        viewModelScope.launch {
                            repository.delete(id)
                            emitEffect(MaterialDetailUiEffect.CloseMaterial)
                        }
                    }
                }

                is MaterialDetailUiEvent.ExtractArchiveEntry -> {
                    extractArchiveEntry(event.entryPath)
                }

                is MaterialDetailUiEvent.CancelArchiveExtraction -> {
                    extractionJobs[event.entryPath]?.cancel()
                }
            }
        }

        private fun observePreviewSource(id: String) {
            sourceJob?.cancel()
            sourceJob =
                viewModelScope.launch {
                    repository.observeById(id).collectLatest { material ->
                        resolvePreviewSource(material)
                    }
                }
        }

        private suspend fun resolvePreviewSource(material: Material?) {
            previewSource.value = MaterialPreviewSourceState()
            resetArchivePreview()
            if (material == null) return

            val localPath = material.localPath
            val remoteKey = material.remoteKey
            when {
                localPath != null -> {
                    previewSource.value = MaterialPreviewSourceState(source = MaterialPreviewSource.Local(localPath))
                    if (material.kind == MaterialKind.ARCHIVE) loadArchivePreview(File(localPath))
                }

                remoteKey == null -> {
                    previewSource.value =
                        MaterialPreviewSourceState(message = "No cached copy is available on this device.")
                }

                else -> {
                    previewSource.value = MaterialPreviewSourceState(loading = true)
                    try {
                        val url = objectStore.getDownloadUrl(ObjectKey(remoteKey)).url
                        previewSource.value = MaterialPreviewSourceState(source = MaterialPreviewSource.Remote(url))
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Throwable) {
                        previewSource.value =
                            MaterialPreviewSourceState(
                                message = "Preview is unavailable until the file can be fetched.",
                            )
                    }
                }
            }
        }

        /** Lists [archiveFile] and turns the verdict into what the archive branch of the preview shows. */
        private suspend fun loadArchivePreview(archiveFile: File) {
            currentArchiveFile = archiveFile
            archivePreview.value = ArchivePreviewUiState(loading = true)
            archivePreview.value =
                when (val listing = archiveEntryExtractor.list(archiveFile)) {
                    is ArchiveListing.Malformed -> {
                        ArchivePreviewUiState(
                            message = "This archive could not be read. It may be corrupt or not a zip file.",
                        )
                    }

                    is ArchiveListing.Listed -> {
                        ArchivePreviewUiState(
                            entries = listing.verdict.accepted.map { ArchiveEntryUiState(it) },
                            message = listing.verdict.rejectionMessage(),
                        )
                    }
                }
        }

        private fun resetArchivePreview() {
            extractionJobs.values.forEach { it.cancel() }
            extractionJobs.clear()
            archivePreview.value = null
            currentArchiveFile = null
        }

        private fun extractArchiveEntry(entryPath: String) {
            val id = materialId.value ?: return
            val archiveFile = currentArchiveFile ?: return
            val target =
                archivePreview.value
                    ?.entries
                    ?.find { it.path == entryPath }
                    ?: return
            if (target.extraction is ArchiveExtractionUiState.Extracting) return

            updateEntry(entryPath) { it.copy(extraction = ArchiveExtractionUiState.Extracting) }
            val job =
                viewModelScope.launch {
                    val result =
                        archiveEntryExtractor.extract(
                            archiveFile = archiveFile,
                            materialId = id,
                            entry = target.entry,
                            isCancelled = { !isActive },
                        )
                    updateEntry(entryPath) { it.copy(extraction = result.toUiState()) }
                }
            // A job cancelled before it ever ran (still queued) never reaches the line above, so
            // this is the only place left to fall the entry back out of "Extracting" for that case.
            job.invokeOnCompletion { cause ->
                if (cause == null) return@invokeOnCompletion
                updateEntry(entryPath) { current ->
                    if (current.extraction is ArchiveExtractionUiState.Extracting) {
                        current.copy(extraction = ArchiveExtractionUiState.Idle)
                    } else {
                        current
                    }
                }
            }
            extractionJobs[entryPath] = job
        }

        private fun updateEntry(
            entryPath: String,
            transform: (ArchiveEntryUiState) -> ArchiveEntryUiState,
        ) {
            archivePreview.update { current ->
                current?.copy(entries = current.entries.map { if (it.path == entryPath) transform(it) else it })
            }
        }

        private companion object {
            const val MATERIAL_ID_KEY = "materialDetail.materialId"
        }
    }

private fun ArchiveExtractionResult.toUiState(): ArchiveExtractionUiState =
    when (this) {
        is ArchiveExtractionResult.Extracted -> {
            ArchiveExtractionUiState.Done(file.path)
        }

        is ArchiveExtractionResult.Cancelled -> {
            ArchiveExtractionUiState.Idle
        }

        is ArchiveExtractionResult.Rejected -> {
            ArchiveExtractionUiState.Failed(reason.toUserMessage())
        }

        is ArchiveExtractionResult.Failed -> {
            ArchiveExtractionUiState.Failed("This entry could not be extracted.")
        }
    }

/** Summarises what [ArchiveVerdict.rejected] refused, or `null` when nothing was. */
private fun ArchiveVerdict.rejectionMessage(): String? {
    if (rejected.isEmpty()) return null
    val summary =
        rejected
            .groupingBy { it.reason }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .joinToString(", ") { (reason, count) -> "$count ${reason.describe(count)}" }
    return if (accepted.isEmpty()) {
        "This archive isn't safe to open: $summary."
    } else {
        "Some entries were skipped: $summary."
    }
}

private fun ArchiveRejection.toUserMessage(): String = "This entry was skipped: ${describe(count = 1)}."

private fun ArchiveRejection.describe(count: Int): String {
    val noun = if (count == 1) "entry" else "entries"
    return when (this) {
        ArchiveRejection.PATH_TRAVERSAL -> "$noun with an unsafe path"
        ArchiveRejection.DUPLICATE_PATH -> "duplicate $noun"
        ArchiveRejection.ENTRY_TOO_LARGE -> "oversized $noun"
        ArchiveRejection.ARCHIVE_TOO_LARGE -> "$noun over the archive size limit"
        ArchiveRejection.TOO_MANY_ENTRIES -> "$noun over the entry-count limit"
        ArchiveRejection.SUSPICIOUS_COMPRESSION_RATIO -> "$noun with a suspicious compression ratio"
        ArchiveRejection.MALFORMED_METADATA -> "$noun with malformed metadata"
    }
}
