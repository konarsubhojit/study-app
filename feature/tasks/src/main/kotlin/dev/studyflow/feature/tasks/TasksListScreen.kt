package dev.studyflow.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.TaskPriority
import dev.studyflow.core.ui.components.StudyFlowListItem
import dev.studyflow.core.ui.components.StudyFlowTopAppBar
import dev.studyflow.core.ui.state.EmptyState
import dev.studyflow.core.ui.state.LoadingState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The task list: quick-add, grouped sections, filters and swipe actions (issue #46).
 */
@Composable
public fun TasksListRoute(
    modifier: Modifier = Modifier,
    onTaskSelect: (String) -> Unit = {},
    viewModel: TasksListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnTaskSelect by rememberUpdatedState(onTaskSelect)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TasksListUiEffect.NavigateToTaskDetail -> currentOnTaskSelect(effect.taskId)
            }
        }
    }

    TasksListScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

@Composable
public fun TasksListScreen(
    state: TasksListUiState,
    onEvent: (TasksListUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            StudyFlowTopAppBar(title = "Tasks")
            QuickAddBar(
                title = state.quickAddTitle,
                dueDate = state.quickAddDueDate,
                onTitleChange = { onEvent(TasksListUiEvent.QuickAddTitleChanged(it)) },
                onDueDateChange = { onEvent(TasksListUiEvent.QuickAddDueDateChanged(it)) },
                onSubmit = { onEvent(TasksListUiEvent.QuickAddSubmitted) },
            )
            FilterBar(state = state, onEvent = onEvent)
            TaskListContent(state = state, onEvent = onEvent)
        }

        state.undo?.let { undo ->
            TasksListUndoSnackbar(undo = undo, onEvent = onEvent)
        }
    }
}

@Composable
private fun TaskListContent(
    state: TasksListUiState,
    onEvent: (TasksListUiEvent) -> Unit,
) {
    when {
        state.loading -> {
            LoadingState(modifier = Modifier.fillMaxSize())
        }

        state.hasNoTasksWhatsoever -> {
            EmptyState(
                message =
                    "No tasks yet. Type a title above and tap Add to create your first one — " +
                        "add Today, Tomorrow or Next week to see it grouped automatically.",
                modifier = Modifier.fillMaxSize(),
            )
        }

        else -> {
            TaskSectionsList(state = state, onEvent = onEvent)
        }
    }
}

@Composable
private fun TaskSectionsList(
    state: TasksListUiState,
    onEvent: (TasksListUiEvent) -> Unit,
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
        )
        taskSection(
            title = "Today",
            tasks = state.today,
            emptyMessage = sectionEmptyMessage("Nothing due today.", state.filter.isNarrowed),
            onEvent = onEvent,
        )
        taskSection(
            title = "Upcoming",
            tasks = state.upcoming,
            emptyMessage = sectionEmptyMessage("Nothing coming up.", state.filter.isNarrowed),
            onEvent = onEvent,
        )
        taskSection(
            title = "Someday",
            tasks = state.someday,
            emptyMessage =
                sectionEmptyMessage("No undated tasks — nice and tidy.", state.filter.isNarrowed),
            onEvent = onEvent,
        )
    }
}

@Composable
private fun BoxScope.TasksListUndoSnackbar(
    undo: TaskListUndo,
    onEvent: (TasksListUiEvent) -> Unit,
) {
    Snackbar(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .padding(MaterialTheme.spacing.medium),
        action = {
            TextButton(
                onClick = { onEvent(TasksListUiEvent.UndoRequested) },
                modifier = Modifier.semantics { contentDescription = "Undo" },
            ) {
                Text(text = "Undo")
            }
        },
        dismissAction = {
            TextButton(onClick = { onEvent(TasksListUiEvent.UndoDismissed) }) {
                Text(text = "Dismiss")
            }
        },
    ) {
        Text(text = undo.message())
    }
}

private fun sectionEmptyMessage(
    default: String,
    narrowed: Boolean,
): String = if (narrowed) "No matching tasks in this section." else default

private fun TaskListUndo.message(): String =
    when (this) {
        is TaskListUndo.Completed -> "Marked \"${original.title}\" done"
        is TaskListUndo.Snoozed -> "Snoozed \"${original.title}\""
    }

private fun LazyListScope.taskSection(
    title: String,
    tasks: List<StudyTask>,
    emptyMessage: String,
    onEvent: (TasksListUiEvent) -> Unit,
) {
    item(key = "header-$title") {
        TaskSectionHeader(title = title, count = tasks.size)
    }
    if (tasks.isEmpty()) {
        item(key = "empty-$title") {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
            )
        }
    } else {
        items(tasks, key = { it.id }) { task ->
            TaskRow(task = task, onEvent = onEvent)
        }
    }
}

@Composable
private fun TaskSectionHeader(
    title: String,
    count: Int,
) {
    Text(
        text = "$title ($count)",
        style = MaterialTheme.typography.titleMedium,
        modifier =
            Modifier
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
    ) {
        StudyFlowListItem(
            headline = task.title,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAddBar(
    title: String,
    dueDate: QuickAddDueDate,
    onTitleChange: (String) -> Unit,
    onDueDateChange: (QuickAddDueDate) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
    ) {
        QuickAddInputRow(title = title, onTitleChange = onTitleChange, onSubmit = onSubmit)
        QuickAddDueDateRow(
            dueDate = dueDate,
            onDueDateChange = onDueDateChange,
            onCustomDateRequest = { showDatePicker = true },
        )
    }

    if (showDatePicker) {
        QuickAddDatePickerDialog(
            onDueDateChange = onDueDateChange,
            onDismissRequest = { showDatePicker = false },
        )
    }
}

@Composable
private fun QuickAddInputRow(
    title: String,
    onTitleChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            label = { Text(text = "Add a task") },
            singleLine = true,
            modifier =
                Modifier
                    .weight(1f)
                    .semantics { contentDescription = "New task title" },
        )
        Button(
            onClick = onSubmit,
            enabled = title.isNotBlank(),
            modifier =
                Modifier.semantics {
                    contentDescription = "Add task"
                    role = Role.Button
                },
        ) {
            Text(text = "Add")
        }
    }
}

@Composable
private fun QuickAddDueDateRow(
    dueDate: QuickAddDueDate,
    onDueDateChange: (QuickAddDueDate) -> Unit,
    onCustomDateRequest: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        modifier = Modifier.padding(top = MaterialTheme.spacing.small),
    ) {
        DueDateChip(
            label = "Today",
            selected = dueDate == QuickAddDueDate.Today,
            onClick = { onDueDateChange(dueDate.toggled(QuickAddDueDate.Today)) },
        )
        DueDateChip(
            label = "Tomorrow",
            selected = dueDate == QuickAddDueDate.Tomorrow,
            onClick = { onDueDateChange(dueDate.toggled(QuickAddDueDate.Tomorrow)) },
        )
        DueDateChip(
            label = "Next week",
            selected = dueDate == QuickAddDueDate.NextWeek,
            onClick = { onDueDateChange(dueDate.toggled(QuickAddDueDate.NextWeek)) },
        )
        DueDateChip(
            label = (dueDate as? QuickAddDueDate.Custom)?.date?.toString() ?: "Custom",
            selected = dueDate is QuickAddDueDate.Custom,
            onClick = {
                if (dueDate is QuickAddDueDate.Custom) {
                    onDueDateChange(QuickAddDueDate.None)
                } else {
                    onCustomDateRequest()
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAddDatePickerDialog(
    onDueDateChange: (QuickAddDueDate) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val pickerState = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onDueDateChange(QuickAddDueDate.Custom(millis.toUtcLocalDate()))
                    }
                    onDismissRequest()
                },
            ) {
                Text(text = "Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = "Cancel")
            }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

private fun Long.toUtcLocalDate(): LocalDate = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date

/** Tapping a selected chip again clears it, which is the only way to get back to "no due date". */
private fun QuickAddDueDate.toggled(target: QuickAddDueDate): QuickAddDueDate =
    if (this == target) QuickAddDueDate.None else target

@Composable
private fun DueDateChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label) },
        modifier = Modifier.semantics { contentDescription = "$label due date" },
    )
}

@Composable
private fun FilterBar(
    state: TasksListUiState,
    onEvent: (TasksListUiEvent) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium),
    ) {
        FilterSearchField(
            query = state.filter.query,
            onQueryChange = { onEvent(TasksListUiEvent.QueryChanged(it)) },
        )
        FilterChipsRow(state = state, onEvent = onEvent)
    }
}

@Composable
private fun FilterSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(text = "Search tasks") },
        singleLine = true,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Search tasks by title, notes or tag" },
    )
}

@Composable
private fun FilterChipsRow(
    state: TasksListUiState,
    onEvent: (TasksListUiEvent) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.small),
    ) {
        item {
            val sortedByPriority = state.filter.sort == TaskSort.PRIORITY
            FilterChip(
                selected = sortedByPriority,
                onClick = {
                    val nextSort = if (sortedByPriority) TaskSort.DUE_DATE else TaskSort.PRIORITY
                    onEvent(TasksListUiEvent.SortChanged(nextSort))
                },
                label = { Text(text = if (sortedByPriority) "Sorted by priority" else "Sorted by due date") },
            )
        }
        items(TaskPriority.entries.toList()) { priority ->
            FilterChip(
                selected = state.filter.priority == priority,
                onClick = {
                    onEvent(
                        TasksListUiEvent.PriorityFilterChanged(
                            if (state.filter.priority == priority) null else priority,
                        ),
                    )
                },
                label = { Text(text = priority.name.lowercase().replaceFirstChar(Char::uppercase)) },
            )
        }
        items(state.filterOptions.subjectIds.toList()) { subjectId ->
            FilterChip(
                selected = state.filter.subjectId == subjectId,
                onClick = {
                    onEvent(
                        TasksListUiEvent.SubjectFilterChanged(
                            if (state.filter.subjectId == subjectId) null else subjectId,
                        ),
                    )
                },
                label = { Text(text = subjectId) },
            )
        }
        items(state.filterOptions.tags.toList()) { tag ->
            FilterChip(
                selected = state.filter.tag == tag,
                onClick = {
                    onEvent(TasksListUiEvent.TagFilterChanged(if (state.filter.tag == tag) null else tag))
                },
                label = { Text(text = "#$tag") },
            )
        }
    }
}
