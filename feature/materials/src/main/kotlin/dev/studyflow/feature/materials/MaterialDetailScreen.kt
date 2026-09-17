package dev.studyflow.feature.materials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.Material
import dev.studyflow.core.ui.components.DurationText
import dev.studyflow.core.ui.components.StudyFlowTopAppBar
import dev.studyflow.core.ui.state.EmptyState
import dev.studyflow.core.ui.state.LoadingState

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
    viewModel: MaterialDetailViewModel = hiltViewModel(key = materialId),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(materialId) {
        viewModel.onEvent(MaterialDetailUiEvent.Load(materialId))
    }

    MaterialDetailScreen(state = state, onBack = onBack, modifier = modifier)
}

@Composable
public fun MaterialDetailScreen(
    state: MaterialDetailUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        StudyFlowTopAppBar(
            title = state.material?.displayName ?: "Material",
            navigationIcon = {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.semantics { contentDescription = "Back to materials" },
                ) {
                    Text(text = "Back")
                }
            },
        )

        when {
            state.loading -> {
                LoadingState(modifier = Modifier.fillMaxSize())
            }

            state.notFound -> {
                EmptyState(
                    message = "This material no longer exists. It may have been deleted on another device.",
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                MaterialDetailContent(material = requireNotNull(state.material))
            }
        }
    }
}

@Composable
private fun MaterialDetailContent(material: Material) {
    Column(
        modifier = Modifier.padding(MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        Text(text = material.displayName, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "${material.mimeType} · ${formatSize(material.sizeBytes)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        material.pageCount?.let { pages ->
            Text(text = "$pages page${if (pages == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium)
        }
        material.duration?.let { duration -> DurationText(duration = duration) }
        material.notes?.let { notes -> Text(text = notes, style = MaterialTheme.typography.bodyMedium) }
    }
}
