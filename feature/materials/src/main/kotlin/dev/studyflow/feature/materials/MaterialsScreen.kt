package dev.studyflow.feature.materials

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailPlaceholder
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.ui.components.DurationText
import dev.studyflow.core.ui.state.EmptyState
import java.util.Locale

/**
 * The materials screen, wired to pickers, a share intent, and the catalogue.
 *
 * The Photo Picker and `ACTION_OPEN_DOCUMENT` need no storage permission, so no permission gate
 * exists here for either of them (issue #37): the platform contracts already return only the URIs
 * the user selected.
 */
@Composable
public fun MaterialsRoute(
    modifier: Modifier = Modifier,
    onOpenMaterial: (String) -> Unit = {},
    viewModel: MaterialsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val photoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (uris.isNotEmpty()) viewModel.onEvent(MaterialsUiEvent.ImportUris(uris.map(Uri::toString)))
        }
    val documentPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) viewModel.onEvent(MaterialsUiEvent.ImportUris(uris.map(Uri::toString)))
        }

    // Read through a remembered snapshot, not the parameter itself: this effect is keyed on
    // `viewModel` alone so it is not restarted every time a caller passes a fresh lambda literal.
    val currentOnOpenMaterial by rememberUpdatedState(onOpenMaterial)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MaterialsUiEffect.NavigateToMaterial -> currentOnOpenMaterial(effect.materialId)
            }
        }
    }

    MaterialsScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onLoadThumbnail = viewModel::loadThumbnail,
        onPickPhotosAndVideos = {
            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        },
        onPickDocuments = { documentPickerLauncher.launch(arrayOf(ALL_MIME_TYPES)) },
        modifier = modifier,
    )
}

@Composable
public fun MaterialsScreen(
    state: MaterialsUiState,
    onEvent: (MaterialsUiEvent) -> Unit,
    onPickPhotosAndVideos: () -> Unit,
    onPickDocuments: () -> Unit,
    modifier: Modifier = Modifier,
    onLoadThumbnail: suspend (Material) -> ImageBitmap? = { null },
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        PickerActions(
            isImporting = state.isImporting,
            onPickPhotosAndVideos = onPickPhotosAndVideos,
            onPickDocuments = onPickDocuments,
        )

        if (state.results.isNotEmpty()) {
            ImportResultsBanner(
                results = state.results,
                onDismiss = { onEvent(MaterialsUiEvent.DismissResults) },
                onViewExisting = { id -> onEvent(MaterialsUiEvent.ViewExisting(id)) },
            )
        }

        if (state.loaded && state.catalog.isEmpty()) {
            EmptyState(message = "Add a photo, a document, or share a file here to start your library.")
        } else {
            MaterialsGrid(
                catalog = state.catalog,
                onLoadThumbnail = onLoadThumbnail,
                onMaterialClick = { id -> onEvent(MaterialsUiEvent.ViewExisting(id)) },
                onRetryUpload = { id -> onEvent(MaterialsUiEvent.RetryUpload(id)) },
            )
        }
    }
}

@Composable
private fun PickerActions(
    isImporting: Boolean,
    onPickPhotosAndVideos: () -> Unit,
    onPickDocuments: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        Text(text = "Materials", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = onPickPhotosAndVideos, enabled = !isImporting) {
            Text(text = "Add photos or videos")
        }
        Button(onClick = onPickDocuments, enabled = !isImporting) {
            Text(text = "Add files")
        }
        if (isImporting) {
            CircularProgressIndicator(modifier = Modifier.padding(top = MaterialTheme.spacing.small))
        }
    }
}

@Composable
private fun ImportResultsBanner(
    results: List<MaterialImportResult>,
    onDismiss: () -> Unit,
    onViewExisting: (String) -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            results.forEach { result -> ImportResultRow(result, onViewExisting) }
            TextButton(onClick = onDismiss) {
                Text(text = "Dismiss")
            }
        }
    }
}

@Composable
private fun ImportResultRow(
    result: MaterialImportResult,
    onViewExisting: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall)) {
        Text(text = result.displayName, style = MaterialTheme.typography.titleSmall)
        when (val status = result.status) {
            is MaterialImportResultStatus.Imported -> {
                Text(text = "Added to your library.", style = MaterialTheme.typography.bodyMedium)
                status.pageCount?.let { pages ->
                    Text(text = "$pages page${if (pages == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                }
                status.duration?.let { duration -> DurationText(duration = duration) }
            }

            is MaterialImportResultStatus.Duplicate -> {
                Text(
                    text = "Already in your library as \"${status.existingDisplayName}\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { onViewExisting(status.existingId) }) {
                    Text(text = "View existing")
                }
            }

            is MaterialImportResultStatus.Rejected -> {
                Text(text = status.message, style = MaterialTheme.typography.bodyMedium)
            }

            is MaterialImportResultStatus.Failed -> {
                Text(text = status.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun MaterialsGrid(
    catalog: List<Material>,
    onLoadThumbnail: suspend (Material) -> ImageBitmap?,
    onMaterialClick: (String) -> Unit,
    onRetryUpload: (String) -> Unit,
) {
    // A grid of real thumbnails is the feature (issue #42): cells are sized adaptively so a phone
    // shows three columns and a tablet shows six, without either of them downloading an original.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = THUMBNAIL_CELL_MIN_SIZE),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = MaterialTheme.spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        items(catalog, key = Material::id) { material ->
            MaterialGridCell(
                material = material,
                onLoadThumbnail = onLoadThumbnail,
                onClick = { onMaterialClick(material.id) },
                onRetryUpload = { onRetryUpload(material.id) },
            )
        }
    }
}

@Composable
private fun MaterialGridCell(
    material: Material,
    onLoadThumbnail: suspend (Material) -> ImageBitmap?,
    onClick: () -> Unit,
    onRetryUpload: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        MaterialThumbnail(material = material, onLoadThumbnail = onLoadThumbnail)
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.small),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            Text(
                text = material.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = formatSize(material.sizeBytes), style = MaterialTheme.typography.bodySmall)
            // A failed upload is the one sync state a tap on the cell cannot resolve — the user
            // needs an explicit way to ask for another attempt, not just to reopen the file
            // (issue #38). Every other sync state renders without it.
            if (material.sync is SyncState.Failed) {
                TextButton(onClick = onRetryUpload) { Text(text = "Retry") }
            }
        }
    }
}

/**
 * One cell's image: the placeholder first, the real thumbnail as soon as there is one.
 *
 * `produceState` is what makes generation cancellable on scroll — it launches into the composition's
 * scope and cancels the moment the cell leaves it, so a half-decoded video frame stops being
 * decoded instead of finishing for a cell nobody is looking at. The keys are the two things that can
 * change the answer — which file this is, and whether there is a local copy to render — so upload
 * progress ticking over does not restart a render that is already running.
 */
@Composable
private fun MaterialThumbnail(
    material: Material,
    onLoadThumbnail: suspend (Material) -> ImageBitmap?,
) {
    val placeholder = remember(material.contentHash) { ThumbnailPlaceholder.of(material.contentHash) }
    // Read through a remembered snapshot so a caller passing a fresh lambda literal on every
    // recomposition does not restart a render that is already in flight.
    val currentOnLoadThumbnail by rememberUpdatedState(onLoadThumbnail)
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, material.id, material.localPath) {
        value = currentOnLoadThumbnail(material)
    }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(placeholder.topColor), Color(placeholder.bottomColor)),
                    ),
                ),
        contentAlignment = Alignment.Center,
    ) {
        val image = thumbnail
        if (image != null) {
            Image(
                bitmap = image,
                // The file name is already shown below the image; repeating it here would make a
                // screen reader read every cell twice.
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = material.kind.name.take(KIND_BADGE_LENGTH),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.surface,
            )
        }
    }
}

internal fun formatSize(sizeBytes: Long): String {
    val kib = sizeBytes / BYTES_PER_KIB.toDouble()
    return when {
        sizeBytes < BYTES_PER_KIB -> "$sizeBytes B"
        kib < BYTES_PER_KIB -> "%.0f KB".format(Locale.ROOT, kib)
        else -> "%.1f MB".format(Locale.ROOT, kib / BYTES_PER_KIB)
    }
}

private const val ALL_MIME_TYPES = "*/*"

/** Three columns on a phone, more on a tablet; the grid decides, not a hard-coded count. */
private val THUMBNAIL_CELL_MIN_SIZE = 112.dp

/** Enough of the kind name to be recognisable on a placeholder without dominating the cell. */
private const val KIND_BADGE_LENGTH = 3
internal const val BYTES_PER_KIB = 1024L
