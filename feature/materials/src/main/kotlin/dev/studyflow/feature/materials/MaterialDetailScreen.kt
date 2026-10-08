package dev.studyflow.feature.materials

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.common.coroutines.StandardDispatcherProvider
import dev.studyflow.core.designsystem.motion.StudyFlowMotion
import dev.studyflow.core.designsystem.motion.StudyFlowSharedElementKeys
import dev.studyflow.core.designsystem.motion.StudyFlowSharedElementScope
import dev.studyflow.core.designsystem.motion.studyFlowSharedElement
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.Material
import dev.studyflow.core.ui.components.DurationText
import dev.studyflow.core.ui.components.StudyFlowTopAppBar
import dev.studyflow.core.ui.state.EmptyState
import dev.studyflow.core.ui.state.LoadingState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The material detail screen: what the catalogue knows about one imported file (issue #37).
 *
 * Reached either from the catalogue list or from a duplicate import's "view existing" action —
 * both dispatch through [dev.studyflow.app.navigation] to the same typed route, so this is the one
 * destination either path ends at rather than a second, screen-local notion of "viewing" a
 * material.
 */
@Composable
public fun MaterialDetailRoute(
    materialId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onStartStudySession: (String?) -> Unit = {},
    sharedElementScope: StudyFlowSharedElementScope? = null,
    viewModel: MaterialDetailViewModel = hiltViewModel(key = materialId),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    val exportSuccessMessage = stringResource(R.string.material_export_success)
    val exportFailedMessage = stringResource(R.string.material_export_failed)
    val exportUnsupportedMessage = stringResource(R.string.material_export_unsupported)

    LaunchedEffect(materialId) {
        viewModel.onEvent(MaterialDetailUiEvent.Load(materialId))
    }

    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                MaterialDetailUiEffect.CloseMaterial -> currentOnBack()
            }
        }
    }

    MaterialDetailScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onShare = { material, source -> shareLocalMaterial(context, material, source) },
        onExport = { material, source ->
            exportScope.launch {
                val result =
                    withContext(StandardDispatcherProvider.io) {
                        exportLocalMaterialToDownloads(context, material, source)
                    }
                Toast
                    .makeText(
                        context,
                        when (result) {
                            MaterialExportResult.Exported -> exportSuccessMessage
                            MaterialExportResult.Failed -> exportFailedMessage
                            MaterialExportResult.Unsupported -> exportUnsupportedMessage
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        },
        onStartStudySession = onStartStudySession,
        sharedElementScope = sharedElementScope,
        modifier = modifier,
    )
}

// Every parameter past the state is one navigation or sharing callback the host owns, so folding
// them into a holder would only move the same list one call site up.
@Suppress("LongParameterList")
@Composable
public fun MaterialDetailScreen(
    state: MaterialDetailUiState,
    onEvent: (MaterialDetailUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onShare: (Material, MaterialPreviewSource?) -> Unit = { _, _ -> },
    onExport: (Material, MaterialPreviewSource?) -> Unit = { _, _ -> },
    onStartStudySession: (String?) -> Unit = {},
    sharedElementScope: StudyFlowSharedElementScope? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        StudyFlowTopAppBar(
            title = state.material?.displayName ?: "Material",
            titleModifier =
                state.material?.let { material ->
                    Modifier.studyFlowSharedElement(
                        StudyFlowSharedElementKeys.materialTitle(material.id),
                        sharedElementScope,
                    )
                } ?: Modifier,
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.semantics { contentDescription = "Back to materials" },
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
        )

        AnimatedContent(
            targetState = state.displayState,
            transitionSpec = {
                fadeIn(StudyFlowMotion.effects()) togetherWith fadeOut(StudyFlowMotion.effects())
            },
            label = "material detail state",
            // Keyed by branch, not snapshot, so a catalogue update to the shown material recomposes
            // in place instead of cross-fading into itself.
            contentKey = { displayState -> displayState::class },
        ) { displayState ->
            when (displayState) {
                DetailDisplayState.Loading -> {
                    LoadingState(modifier = Modifier.fillMaxSize())
                }

                DetailDisplayState.NotFound -> {
                    EmptyState(
                        message = "This material no longer exists. It may have been deleted on another device.",
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                is DetailDisplayState.Content -> {
                    MaterialDetailContent(
                        material = displayState.material,
                        state = displayState.state,
                        onEvent = onEvent,
                        onShare = onShare,
                        onExport = onExport,
                        onStartStudySession = onStartStudySession,
                        sharedElementScope = sharedElementScope,
                    )
                }
            }
        }
    }
}

@Composable
private fun MaterialDetailContent(
    material: Material,
    state: MaterialDetailUiState,
    onEvent: (MaterialDetailUiEvent) -> Unit,
    onShare: (Material, MaterialPreviewSource?) -> Unit,
    onExport: (Material, MaterialPreviewSource?) -> Unit,
    onStartStudySession: (String?) -> Unit,
    sharedElementScope: StudyFlowSharedElementScope?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        Text(
            text = "${material.mimeType} · ${formatSize(material.sizeBytes)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        material.pageCount?.let { pages ->
            Text(text = "$pages page${if (pages == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium)
        }

        material.duration?.let { duration -> DurationText(duration = duration) }
        material.notes?.let { notes -> Text(text = notes, style = MaterialTheme.typography.bodyMedium) }
        PreviewActions(
            material = material,
            source = state.previewSource,
            onShare = onShare,
            onExport = onExport,
            onDelete = { onEvent(MaterialDetailUiEvent.DeleteMaterial) },
            onStartStudySession = onStartStudySession,
        )
        MaterialPreview(
            material = material,
            state = state,
            onPdfPageChange = { pageIndex -> onEvent(MaterialDetailUiEvent.PdfPageChanged(pageIndex)) },
            onPlaybackChange = { position, speed ->
                onEvent(MaterialDetailUiEvent.PlaybackChanged(position, speed))
            },
            onExtractArchiveEntry = { path -> onEvent(MaterialDetailUiEvent.ExtractArchiveEntry(path)) },
            onCancelArchiveExtraction = { path -> onEvent(MaterialDetailUiEvent.CancelArchiveExtraction(path)) },
            modifier =
                Modifier
                    .weight(1f)
                    .studyFlowSharedElement(
                        StudyFlowSharedElementKeys.materialPreview(material.id),
                        sharedElementScope,
                    ),
        )
    }
}

/**
 * Which branch of the detail screen to show, carrying what that branch renders.
 *
 * [Content] holds the material and state it was chosen with because `AnimatedContent` keeps the
 * outgoing branch composed while it fades out. Deleting a material makes the repository emit `null`
 * before navigation removes the screen, so a branch that re-read `MaterialDetailUiState.material`
 * would find nothing to render mid-transition; the snapshot keeps the last frame intact instead.
 */
private sealed interface DetailDisplayState {
    data object Loading : DetailDisplayState

    data object NotFound : DetailDisplayState

    data class Content(
        val material: Material,
        val state: MaterialDetailUiState,
    ) : DetailDisplayState
}

private val MaterialDetailUiState.displayState: DetailDisplayState
    get() {
        val shown = material
        return when {
            loading -> DetailDisplayState.Loading
            shown == null -> DetailDisplayState.NotFound
            else -> DetailDisplayState.Content(material = shown, state = this)
        }
    }
