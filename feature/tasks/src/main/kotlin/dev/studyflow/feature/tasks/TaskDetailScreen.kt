package dev.studyflow.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.RecurrenceEnd
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.RecurrenceRule
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.ReminderTrigger
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subtask
import dev.studyflow.core.ui.components.StudyFlowTopAppBar
import dev.studyflow.core.ui.state.EmptyState
import dev.studyflow.core.ui.state.LoadingState
import kotlin.time.Duration

/**
 * The task detail screen: notes, subtasks, reminders, recurrence and a study-session shortcut
 * (issue #46).
 */
@Composable
public fun TaskDetailRoute(
    taskId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onStartStudySession: (taskId: String, subjectId: String?) -> Unit = { _, _ -> },
    viewModel: TaskDetailViewModel = hiltViewModel(key = taskId),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnStartStudySession by rememberUpdatedState(onStartStudySession)

    LaunchedEffect(taskId) {
        viewModel.onEvent(TaskDetailUiEvent.Load(taskId))
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TaskDetailUiEffect.StartStudySession -> {
                    currentOnStartStudySession(effect.taskId, effect.subjectId)
                }
            }
        }
    }

    TaskDetailScreen(state = state, onEvent = viewModel::onEvent, onBack = onBack, modifier = modifier)
}

@Composable
public fun TaskDetailScreen(
    state: TaskDetailUiState,
    onEvent: (TaskDetailUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        StudyFlowTopAppBar(
            title = state.task?.title ?: "Task",
            navigationIcon = {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.semantics { contentDescription = "Back to tasks" },
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
                    message = "This task no longer exists. It may have been deleted on another device.",
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                val task = requireNotNull(state.task)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(MaterialTheme.spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
                ) {
                    item { CompletionRow(task = task, onEvent = onEvent) }
                    item { TitleField(task = task, onEvent = onEvent) }
                    item { NotesField(task = task, onEvent = onEvent) }
                    item { SubjectMaterialLinks(task = task) }
                    item {
                        SubtaskSection(
                            subtasks = task.subtasks,
                            newSubtaskTitle = state.newSubtaskTitle,
                            onEvent = onEvent,
                        )
                    }
                    item { ReminderSection(reminders = task.reminders, onEvent = onEvent) }
                    item { RecurrenceSection(recurrence = task.recurrence, onEvent = onEvent) }
                    item { StartStudySessionButton(onEvent = onEvent) }
                }
            }
        }
    }
}

@Composable
private fun CompletionRow(
    task: StudyTask,
    onEvent: (TaskDetailUiEvent) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = task.isCompleted,
            onCheckedChange = { onEvent(TaskDetailUiEvent.CompletionToggled) },
            modifier =
                Modifier.semantics {
                    contentDescription = if (task.isCompleted) "Mark task as not done" else "Mark task as done"
                },
        )
        Text(text = if (task.isCompleted) "Completed" else "Not completed yet")
    }
}

@Composable
private fun TitleField(
    task: StudyTask,
    onEvent: (TaskDetailUiEvent) -> Unit,
) {
    OutlinedTextField(
        value = task.title,
        onValueChange = { onEvent(TaskDetailUiEvent.TitleChanged(it)) },
        label = { Text(text = "Title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NotesField(
    task: StudyTask,
    onEvent: (TaskDetailUiEvent) -> Unit,
) {
    OutlinedTextField(
        value = task.notes.orEmpty(),
        onValueChange = { onEvent(TaskDetailUiEvent.NotesChanged(it)) },
        label = { Text(text = "Notes") },
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Task notes" },
    )
}

/**
 * A plain informational label rather than a picker: no subject or material repository exists yet
 * to resolve an id to a name (issue #46 explicitly scopes a real picker out until one does).
 */
@Composable
private fun SubjectMaterialLinks(task: StudyTask) {
    if (task.subjectId == null && task.materialId == null) return
    Column {
        task.subjectId?.let { Text(text = "Subject: $it", style = MaterialTheme.typography.bodyMedium) }
        task.materialId?.let { Text(text = "Material: $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun SubtaskSection(
    subtasks: List<Subtask>,
    newSubtaskTitle: String,
    onEvent: (TaskDetailUiEvent) -> Unit,
) {
    Column {
        SectionHeader(title = "Subtasks")
        subtasks.forEach { subtask ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = subtask.isCompleted,
                    onCheckedChange = { onEvent(TaskDetailUiEvent.SubtaskToggled(subtask.id)) },
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                if (subtask.isCompleted) {
                                    "Mark ${subtask.title} as not done"
                                } else {
                                    "Mark ${subtask.title} as done"
                                }
                        },
                )
                Text(text = subtask.title, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { onEvent(TaskDetailUiEvent.SubtaskRemoved(subtask.id)) },
                    modifier = Modifier.semantics { contentDescription = "Remove ${subtask.title}" },
                ) {
                    Text(text = "Remove")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newSubtaskTitle,
                onValueChange = { onEvent(TaskDetailUiEvent.NewSubtaskTitleChanged(it)) },
                label = { Text(text = "New subtask") },
                singleLine = true,
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics { contentDescription = "New subtask title" },
            )
            TextButton(
                onClick = { onEvent(TaskDetailUiEvent.SubtaskAdded) },
                enabled = newSubtaskTitle.isNotBlank(),
            ) {
                Text(text = "Add")
            }
        }
    }
}

@Composable
private fun ReminderSection(
    reminders: List<Reminder>,
    onEvent: (TaskDetailUiEvent) -> Unit,
) {
    Column {
        SectionHeader(title = "Reminders")
        reminders.forEach { reminder ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = reminder.trigger.describe(), modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { onEvent(TaskDetailUiEvent.ReminderRemoved(reminder.id)) },
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                "Remove reminder ${reminder.trigger.describe()}"
                        },
                ) {
                    Text(text = "Remove")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            ReminderPreset.entries.forEach { preset ->
                val alreadyAdded = reminders.any { it.trigger == preset.trigger }
                FilterChip(
                    selected = false,
                    enabled = !alreadyAdded,
                    onClick = { onEvent(TaskDetailUiEvent.ReminderAdded(preset)) },
                    label = { Text(text = preset.label) },
                )
            }
        }
    }
}

private fun ReminderTrigger.describe(): String =
    when (this) {
        is ReminderTrigger.BeforeDue -> {
            if (leadTime == Duration.ZERO) "At due time" else "$leadTime before due"
        }

        is ReminderTrigger.AtInstant -> {
            "At $instant"
        }
    }

@Composable
private fun RecurrenceSection(
    recurrence: RecurrenceRule?,
    onEvent: (TaskDetailUiEvent) -> Unit,
) {
    var interval by remember(recurrence?.frequency) { mutableIntStateOf(recurrence?.interval ?: 1) }

    Column {
        SectionHeader(title = "Repeat")
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            RecurrenceOptionChip(label = "None", selected = recurrence == null) {
                onEvent(TaskDetailUiEvent.RecurrenceChanged(null))
            }
            RecurrenceFrequency.entries.forEach { frequency ->
                RecurrenceOptionChip(
                    label = frequency.name.lowercase().replaceFirstChar(Char::uppercase),
                    selected =
                        recurrence?.frequency == frequency,
                ) {
                    onEvent(
                        TaskDetailUiEvent.RecurrenceChanged(
                            RecurrenceRule(
                                frequency = frequency,
                                interval = interval,
                                end =
                                    recurrence?.end ?: RecurrenceEnd.Never,
                            ),
                        ),
                    )
                }
            }
        }

        if (recurrence != null) {
            OutlinedTextField(
                value = interval.toString(),
                onValueChange = { text ->
                    val parsed = text.toIntOrNull()?.coerceAtLeast(1) ?: return@OutlinedTextField
                    interval = parsed
                    onEvent(TaskDetailUiEvent.RecurrenceChanged(recurrence.copy(interval = parsed)))
                },
                label = { Text(text = "Every N ${recurrence.frequency.name.lowercase()} periods") },
                singleLine = true,
                modifier = Modifier.semantics { contentDescription = "Recurrence interval" },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                RecurrenceOptionChip(label = "Never ends", selected = recurrence.end == RecurrenceEnd.Never) {
                    onEvent(TaskDetailUiEvent.RecurrenceChanged(recurrence.copy(end = RecurrenceEnd.Never)))
                }
                val occurrenceCount =
                    (recurrence.end as? RecurrenceEnd.AfterOccurrences)?.count ?: DEFAULT_OCCURRENCE_COUNT
                RecurrenceOptionChip(
                    label = "After $occurrenceCount occurrences",
                    selected = recurrence.end is RecurrenceEnd.AfterOccurrences,
                ) {
                    onEvent(
                        TaskDetailUiEvent.RecurrenceChanged(
                            recurrence.copy(end = RecurrenceEnd.AfterOccurrences(occurrenceCount)),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecurrenceOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label) },
        modifier = Modifier.semantics { contentDescription = label },
    )
}

@Composable
private fun StartStudySessionButton(onEvent: (TaskDetailUiEvent) -> Unit) {
    Button(
        onClick = { onEvent(TaskDetailUiEvent.StartStudySessionRequested) },
        modifier = Modifier.semantics { contentDescription = "Start study session for this task" },
    ) {
        Text(text = "Start study session")
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

/** The occurrence count the "After N" recurrence-end chip offers; a reasonable default, not a limit. */
private const val DEFAULT_OCCURRENCE_COUNT = 10
