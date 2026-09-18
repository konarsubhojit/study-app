package dev.studyflow.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.theme.spacing

@Composable
public fun HomeRoute(
    onOpenTimer: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenMaterial: (String) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        actions = HomeActions(onOpenTimer, onOpenTask, onOpenMaterial, onOpenHistory),
        modifier = modifier,
    )
}

/** A no-spinner, local-first summary of progress and the next useful action. */
@Composable
public fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        item {
            Text("Today", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Button(onClick = actions.onOpenTimer, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.activeSession == null) "Start a focus session" else "Resume focus session")
            }
        }
        item {
            SummaryCard(
                title = "Focus time",
                detail = "${state.focusTime.inWholeMinutes} of ${state.focusGoal.inWholeMinutes} min",
                onClick = actions.onOpenHistory,
            )
        }
        item {
            SummaryCard(
                title = "Current streak",
                detail = if (state.streakDays == 1) "1 day" else "${state.streakDays} days",
                onClick = actions.onOpenHistory,
            )
        }
        state.activeSession?.let { session ->
            item {
                SummaryCard(
                    title =
                        if (session.elapsed.hasUnverifiedTime) {
                            "Session recovered after reboot"
                        } else {
                            "Session in progress"
                        },
                    detail = session.note ?: "Tap to continue",
                    onClick = actions.onOpenTimer,
                )
            }
        }
        state.nextTask?.let { task ->
            item {
                SummaryCard(title = "Next due", detail = task.title, onClick = { actions.onOpenTask(task.id) })
            }
        }
        if (state.recentMaterials.isNotEmpty()) {
            item { Text("Recent materials", style = MaterialTheme.typography.titleLarge) }
            items(state.recentMaterials, key = { it.id }) { material ->
                SummaryCard(
                    title = material.displayName,
                    detail =
                        material.kind.name
                            .lowercase()
                            .replaceFirstChar(Char::uppercase),
                    onClick = { actions.onOpenMaterial(material.id) },
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
