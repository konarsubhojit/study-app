package dev.studyflow.feature.materials

import android.content.ActivityNotFoundException
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.core.net.toUri
import coil3.compose.SubcomposeAsyncImage
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.MaterialKind
import dev.studyflow.core.ui.state.EmptyState
import dev.studyflow.core.ui.state.LoadingState

@Composable
internal fun MaterialPreview(
    material: Material,
    state: MaterialDetailUiState,
    onPdfPageChange: (Int) -> Unit,
    onPlaybackChange: (Long, Float) -> Unit,
    onExtractArchiveEntry: (String) -> Unit,
    onCancelArchiveExtraction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = state.previewSource
    when {
        state.previewSourceLoading -> {
            LoadingState(modifier = modifier.fillMaxSize())
        }

        state.previewSourceMessage != null -> {
            EmptyState(message = state.previewSourceMessage, modifier = modifier.fillMaxSize())
        }

        source == null -> {
            EmptyState(message = "No preview is available for this material.", modifier = modifier)
        }

        material.kind == MaterialKind.IMAGE -> {
            ImagePreview(material = material, source = source, modifier = modifier)
        }

        material.kind == MaterialKind.VIDEO || material.kind == MaterialKind.AUDIO -> {
            MediaPreview(material = material, source = source, onPlaybackChange = onPlaybackChange, modifier = modifier)
        }

        material.kind == MaterialKind.PDF -> {
            PdfPreview(material = material, source = source, onPageChange = onPdfPageChange, modifier = modifier)
        }

        material.kind == MaterialKind.ARCHIVE -> {
            ArchivePreview(
                archivePreview = state.archivePreview,
                onExtract = onExtractArchiveEntry,
                onCancel = onCancelArchiveExtraction,
                modifier = modifier,
            )
        }

        material.kind == MaterialKind.TEXT -> {
            TextPreview(source = source, modifier = modifier)
        }

        else -> {
            // DOCUMENT, SPREADSHEET, PRESENTATION and OTHER: no bundled renderer, so the only
            // in-app affordance is handing the file to whatever the device already has installed.
            OpenExternallyPreview(material = material, source = source, modifier = modifier)
        }
    }
}

@Composable
private fun ImagePreview(
    material: Material,
    source: MaterialPreviewSource,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val transformableState =
        rememberTransformableState { _, zoomChange, offsetChange, _ ->
            scale = (scale * zoomChange).coerceIn(IMAGE_ZOOM_MIN_SCALE, IMAGE_ZOOM_MAX_SCALE)
            offsetX += offsetChange.x
            offsetY += offsetChange.y
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = source.previewModel(),
            contentDescription = "${material.displayName} preview",
            contentScale = ContentScale.Fit,
            error = { state ->
                LaunchedEffect(state.result.throwable) {
                    Log.w(IMAGE_PREVIEW_TAG, "Image preview failed", state.result.throwable)
                }
                EmptyState(
                    message = "This image could not be opened.",
                    modifier = Modifier.fillMaxSize(),
                )
            },
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }.transformable(transformableState),
        )
    }
}

/**
 * The fallback preview for kinds with no bundled renderer — [MaterialKind.DOCUMENT],
 * [MaterialKind.SPREADSHEET], [MaterialKind.PRESENTATION] and [MaterialKind.OTHER] (issue #40).
 *
 * "Open" hands the cached file to whatever the device already has installed instead of shipping a
 * document engine. When nothing can open it, [ActivityNotFoundException] is caught rather than
 * left to crash the screen, and the existing Share/Export actions in [PreviewActions] stay usable
 * either way.
 */
@Composable
private fun OpenExternallyPreview(
    material: Material,
    source: MaterialPreviewSource?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var noViewerAvailable by remember(material.id) { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        Text(
            text = "This file type is saved in your library, but has no in-app preview yet.",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (noViewerAvailable) {
            Text(
                text = "No app on this device can open this file. You can still share or export it below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = {
                noViewerAvailable =
                    source !is MaterialPreviewSource.Local ||
                    source.uri.toLocalFileOrNull()?.let { context.openLocalFile(it, material.mimeType) } != true
            },
            enabled = source is MaterialPreviewSource.Local,
        ) {
            Text(text = "Open")
        }
    }
}

internal fun MaterialPreviewSource.previewModel(): Any =
    when (this) {
        is MaterialPreviewSource.Local -> toPreviewUri()
        is MaterialPreviewSource.Remote -> uri
    }

internal fun MaterialPreviewSource.playerUri(): Uri =
    when (this) {
        is MaterialPreviewSource.Local -> toPreviewUri()
        is MaterialPreviewSource.Remote -> uri.toUri()
    }

private fun MaterialPreviewSource.Local.toPreviewUri(): Uri {
    val parsed = uri.toUri()
    if (parsed.scheme == "file") return parsed
    return uri.toLocalFileOrNull()?.let(Uri::fromFile) ?: parsed
}

private const val IMAGE_ZOOM_MIN_SCALE = 1f
private const val IMAGE_ZOOM_MAX_SCALE = 5f

private const val IMAGE_PREVIEW_TAG = "ImagePreview"
