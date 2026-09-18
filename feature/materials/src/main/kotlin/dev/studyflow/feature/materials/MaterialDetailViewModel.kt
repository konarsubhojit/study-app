package dev.studyflow.feature.materials

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.model.Material
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject

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
    ) : MviViewModel<MaterialDetailUiEvent, MaterialDetailUiEffect>(savedStateHandle) {
        private val materialId = MutableStateFlow(savedStateHandle.get<String>(MATERIAL_ID_KEY))
        private val previewSource = MutableStateFlow(MaterialPreviewSourceState())
        private var sourceJob: Job? = null

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
            ) { state, source ->
                state.copy(
                    previewSource = source.source,
                    previewSourceLoading = source.loading,
                    previewSourceMessage = source.message,
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
            if (material == null) return

            material.localPath?.let { localPath ->
                previewSource.value = MaterialPreviewSourceState(source = MaterialPreviewSource.Local(localPath))
                return
            }

            val remoteKey = material.remoteKey
            if (remoteKey == null) {
                previewSource.value =
                    MaterialPreviewSourceState(message = "No cached copy is available on this device.")
                return
            }

            previewSource.value = MaterialPreviewSourceState(loading = true)
            try {
                val url = objectStore.getDownloadUrl(ObjectKey(remoteKey)).url
                previewSource.value = MaterialPreviewSourceState(source = MaterialPreviewSource.Remote(url))
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Throwable) {
                previewSource.value =
                    MaterialPreviewSourceState(message = "Preview is unavailable until the file can be fetched.")
            }
        }

        private companion object {
            const val MATERIAL_ID_KEY = "materialDetail.materialId"
        }
    }
