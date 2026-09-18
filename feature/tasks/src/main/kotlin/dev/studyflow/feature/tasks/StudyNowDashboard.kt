package dev.studyflow.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.StudyTask

/** The task-focused part of the home dashboard. */
@Composable
public fun StudyNowDashboardRoute(
    onStudyNow: (taskId: String, subjectId: String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TasksListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StudyNowDashboard(
        task = state.nextDueTask,
        onStudyNow = onStudyNow,
        modifier = modifier,
    )
}

@Composable
private fun StudyNowDashboard(
    task: StudyTask?,
    onStudyNow: (taskId: String, subjectId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(MaterialTheme.spacing.large),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        Text(text = "Home", style = MaterialTheme.typography.headlineMedium)
        if (task == null) {
            Text(text = "No upcoming tasks")
        } else {
            Text(text = "Next due: ${task.title}", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { onStudyNow(task.id, task.subjectId) }) {
                Text(text = "Study now")
            }
        }
    }
}

private val TasksListUiState.nextDueTask: StudyTask?
    get() = overdue.firstOrNull() ?: today.firstOrNull() ?: upcoming.firstOrNull()
