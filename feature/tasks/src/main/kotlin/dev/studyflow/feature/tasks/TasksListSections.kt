package dev.studyflow.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.studyflow.core.designsystem.motion.StudyFlowSharedElementKeys
import dev.studyflow.core.designsystem.motion.StudyFlowSharedElementScope
import dev.studyflow.core.designsystem.motion.studyFlowSharedElement
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.ui.components.StudyFlowListItem

@Composable
internal fun TaskSectionsList(
    state: TasksListUiState,
    onEvent: (TasksListUiEvent) -> Unit,
    sharedElementScope: StudyFlowSharedElementScope?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MaterialTheme.spacing.huge),
    ) {
        taskSection(
            title = "Overdue",
            tasks = state.overdue,
            emptyMessage = sectionEmptyMessage("Nothing overdue.", state.filter.isNarrowed),
            onEvent = onEvent,
            sharedElementScope = sharedElementScope,
        )
        taskSection(
            title = "Today",
            tasks = state.today,
            emptyMessage = sectionEmptyMessage("Nothing due today.", state.filter.isNarrowed),
            onEvent = onEvent,
            sharedElementScope = sharedElementScope,
        )
        taskSection(
            title = "Upcoming",
            tasks = state.upcoming,
            emptyMessage = sectionEmptyMessage("Nothing coming up.", state.filter.isNarrowed),
            onEvent = onEvent,
            sharedElementScope = sharedElementScope,
        )
        taskSection(
            title = "Someday",
            tasks = state.someday,
            emptyMessage =
                sectionEmptyMessage("No undated tasks — nice and tidy.", state.filter.isNarrowed),
            onEvent = onEvent,
            sharedElementScope = sharedElementScope,
        )
    }
}

private fun sectionEmptyMessage(
    default: String,
    narrowed: Boolean,
): String = if (narrowed) "No matching tasks in this section." else default

private fun LazyListScope.taskSection(
    title: String,
    tasks: List<StudyTask>,
    emptyMessage: String,
    onEvent: (TasksListUiEvent) -> Unit,
    sharedElementScope: StudyFlowSharedElementScope?,
) {
    item(key = "header-$title") {
        TaskSectionHeader(title = title, count = tasks.size, modifier = Modifier.animateItem())
    }
    if (tasks.isEmpty()) {
        item(key = "empty-$title") {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
            )
        }
    } else {
        items(tasks, key = { it.id }) { task ->
            TaskRow(
                task = task,
                onEvent = onEvent,
                modifier = Modifier.animateItem(),
                sharedElementScope = sharedElementScope,
            )
        }
    }
}

@Composable
private fun TaskSectionHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "$title ($count)",
        style = MaterialTheme.typography.titleMedium,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small)
                .semantics { heading() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskRow(
    task: StudyTask,
    onEvent: (TasksListUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    sharedElementScope: StudyFlowSharedElementScope? = null,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val currentOnEvent by rememberUpdatedState(onEvent)

    // `confirmValueChange` is deprecated with no replacement that keeps a swipe non-destructive, so
    // the completion/snooze action is triggered from the settled value instead, and the box is
    // snapped back immediately: the task list, not the swipe gesture, is what removes or reorders
    // this row once the repository reflects the change.
    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> currentOnEvent(TasksListUiEvent.TaskCompletionToggled(task))
            SwipeToDismissBoxValue.EndToStart -> currentOnEvent(TasksListUiEvent.TaskSnoozed(task))
            SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
        }
        dismissState.snapTo(SwipeToDismissBoxValue.Settled)
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { SwipeBackground(direction = dismissState.dismissDirection) },
        modifier = modifier,
    ) {
        StudyFlowListItem(
            headline = task.title,
            headlineModifier =
                Modifier.studyFlowSharedElement(
                    StudyFlowSharedElementKeys.taskTitle(task.id),
                    sharedElementScope,
                ),
            supportingText = task.notes,
            overlineText = task.dueAt?.let { "Due ${it.date}" },
            onClick = { onEvent(TasksListUiEvent.TaskOpened(task.id)) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = task.isCompleted,
                        onCheckedChange = { onEvent(TasksListUiEvent.TaskCompletionToggled(task)) },
                        modifier =
                            Modifier.semantics {
                                contentDescription =
                                    if (task.isCompleted) {
                                        "Mark ${task.title} as not done"
                                    } else {
                                        "Mark ${task.title} as done"
                                    }
                            },
                    )
                    TextButton(
                        onClick = { onEvent(TasksListUiEvent.TaskSnoozed(task)) },
                        modifier =
                            Modifier.semantics {
                                contentDescription = "Snooze ${task.title} to tomorrow"
                            },
                    ) {
                        Text(text = "Snooze")
                    }
                }
            },
        )
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue?) {
    val (label, containerColor, contentColor) =
        when (direction) {
            SwipeToDismissBoxValue.StartToEnd -> {
                Triple(
                    "Complete",
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            SwipeToDismissBoxValue.EndToStart -> {
                Triple(
                    "Snooze",
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            else -> {
                Triple("", MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
            }
        }
    val alignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(containerColor)
                .padding(MaterialTheme.spacing.medium),
        contentAlignment = alignment,
    ) {
        if (label.isNotEmpty()) {
            Text(text = label, color = contentColor)
        }
    }
}
