package dev.studyflow.feature.materials

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.model.Material
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
) : UiState {
    public val notFound: Boolean
        get() = !loading && material == null
}

public sealed interface MaterialDetailUiEvent : UiEvent {
    /** Requests the material with [materialId]; sent once, when the screen is first shown. */
    public data class Load(
        val materialId: String,
    ) : MaterialDetailUiEvent
}

public sealed interface MaterialDetailUiEffect : UiEffect

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
public class MaterialDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: MaterialRepository,
    ) : MviViewModel<MaterialDetailUiEvent, MaterialDetailUiEffect>(savedStateHandle) {
        private val materialId = MutableStateFlow(savedStateHandle.get<String>(MATERIAL_ID_KEY))

        public val state: StateFlow<MaterialDetailUiState> =
            materialId
                .flatMapLatest { id ->
                    id
                        ?.let { repository.observeById(it) }
                        ?.map { material -> MaterialDetailUiState(loading = false, material = material) }
                        ?: flowOf(MaterialDetailUiState())
                }.stateInViewModel(MaterialDetailUiState())

        override fun onEvent(event: MaterialDetailUiEvent) {
            when (event) {
                is MaterialDetailUiEvent.Load -> {
                    savedStateHandle[MATERIAL_ID_KEY] = event.materialId
                    materialId.value = event.materialId
                }
            }
        }

        private companion object {
            const val MATERIAL_ID_KEY = "materialDetail.materialId"
        }
    }
