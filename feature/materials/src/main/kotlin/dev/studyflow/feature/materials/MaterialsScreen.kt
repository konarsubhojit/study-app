package dev.studyflow.feature.materials

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.Material
import dev.studyflow.core.ui.components.DurationText
import dev.studyflow.core.ui.components.StudyFlowListItem
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
            MaterialsList(
                catalog = state.catalog,
                onMaterialClick = { id -> onEvent(MaterialsUiEvent.ViewExisting(id)) },
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
private fun MaterialsList(
    catalog: List<Material>,
    onMaterialClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
        items(catalog, key = Material::id) { material ->
            StudyFlowListItem(
                headline = material.displayName,
                supportingText = "${material.mimeType} · ${formatSize(material.sizeBytes)}",
                onClick = { onMaterialClick(material.id) },
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
internal const val BYTES_PER_KIB = 1024L
