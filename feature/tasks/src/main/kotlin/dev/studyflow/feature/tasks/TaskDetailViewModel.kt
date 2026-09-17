package dev.studyflow.feature.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.domain.tasks.TaskRepository
import dev.studyflow.core.model.RecurrenceRule
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subtask
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Everything the detail screen renders for one task, plus the checklist draft the user is typing.
 *
 * [loading] is true only before the first snapshot for the requested id has arrived; a task that
 * genuinely does not exist (deleted, or a stale deep link) settles into [task] being `null` with
 * [loading] false, which is what lets the screen tell "still loading" apart from "not found".
 */
public data class TaskDetailUiState(
    val loading: Boolean = true,
    val task: StudyTask? = null,
    val newSubtaskTitle: String = "",
) : UiState {
    public val notFound: Boolean
        get() = !loading && task == null
}

public sealed interface TaskDetailUiEvent : UiEvent {
    /** Requests the task with [taskId]; sent once, when the screen is first shown. */
    public data class Load(
        val taskId: String,
    ) : TaskDetailUiEvent

    public data class TitleChanged(
        val title: String,
    ) : TaskDetailUiEvent

    public data class NotesChanged(
        val notes: String,
    ) : TaskDetailUiEvent

    public data object CompletionToggled : TaskDetailUiEvent

    public data class NewSubtaskTitleChanged(
        val title: String,
    ) : TaskDetailUiEvent

    public data object SubtaskAdded : TaskDetailUiEvent

    public data class SubtaskToggled(
        val subtaskId: String,
    ) : TaskDetailUiEvent

    public data class SubtaskRemoved(
        val subtaskId: String,
    ) : TaskDetailUiEvent

    public data class ReminderAdded(
        val preset: ReminderPreset,
    ) : TaskDetailUiEvent

    public data class ReminderRemoved(
        val reminderId: String,
    ) : TaskDetailUiEvent

    public data class RecurrenceChanged(
        val recurrence: RecurrenceRule?,
    ) : TaskDetailUiEvent

    /** "Start study session"; kept as a stub effect until a task-aware session-start API exists. */
    public data object StartStudySessionRequested : TaskDetailUiEvent
}

public sealed interface TaskDetailUiEffect : UiEffect {
    /**
     * Asks the app shell to begin a study session for this task.
     *
     * Deliberately a bare stub: [dev.studyflow.core.domain.session.SessionRepository.execute] needs
     * a [dev.studyflow.core.model.TimeAnchor] and an idempotency key that belong to the timer
     * feature's own command flow. Wiring this to a real session takes a navigation decision the app
     * shell owns, not a new feature-to-feature dependency from here (`:feature:tasks` may not depend
     * on `:feature:timer`).
     */
    public data class StartStudySession(
        val taskId: String,
        val subjectId: String?,
    ) : TaskDetailUiEffect
}

/**
 * Drives the task detail screen: notes, subtasks, reminders, recurrence and completion, all
 * expressed as reads and writes of the one [StudyTask] aggregate through [TaskRepository.save].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
public class TaskDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: TaskRepository,
        private val clock: Clock,
    ) : MviViewModel<TaskDetailUiEvent, TaskDetailUiEffect>(savedStateHandle) {
        private val taskId = MutableStateFlow(savedStateHandle.get<String>(TASK_ID_KEY))
        private val newSubtaskTitle = MutableStateFlow("")

        private val loadResult: StateFlow<TaskLoadResult> =
            taskId
                .flatMapLatest { id ->
                    id?.let { repository.observeTask(it).map(TaskLoadResult::Loaded) } ?: flowOf(TaskLoadResult.Idle)
                }.stateInViewModel(TaskLoadResult.Idle)

        public val state: StateFlow<TaskDetailUiState> =
            combine(loadResult, newSubtaskTitle) { result, subtaskTitle ->
                when (result) {
                    TaskLoadResult.Idle -> {
                        TaskDetailUiState(loading = true, newSubtaskTitle = subtaskTitle)
                    }

                    is TaskLoadResult.Loaded -> {
                        TaskDetailUiState(loading = false, task = result.task, newSubtaskTitle = subtaskTitle)
                    }
                }
            }.stateInViewModel(TaskDetailUiState())

        override fun onEvent(event: TaskDetailUiEvent) {
            when (event) {
                is TaskDetailUiEvent.Load -> {
                    load(event.taskId)
                }

                is TaskDetailUiEvent.TitleChanged -> {
                    val title = event.title
                    mutate { it.copy(title = title) }
                }

                is TaskDetailUiEvent.NotesChanged -> {
                    val notes = event.notes
                    mutate { it.copy(notes = notes.ifBlank { null }) }
                }

                TaskDetailUiEvent.CompletionToggled -> {
                    mutate { task -> task.copy(completedAt = if (task.isCompleted) null else clock.now()) }
                }

                is TaskDetailUiEvent.NewSubtaskTitleChanged -> {
                    newSubtaskTitle.value = event.title
                }

                TaskDetailUiEvent.SubtaskAdded -> {
                    addSubtask()
                }

                is TaskDetailUiEvent.SubtaskToggled -> {
                    val subtaskId = event.subtaskId
                    mutate { task ->
                        task.copy(
                            subtasks =
                                task.subtasks.map { subtask ->
                                    if (subtask.id != subtaskId) {
                                        subtask
                                    } else {
                                        subtask.copy(completedAt = if (subtask.isCompleted) null else clock.now())
                                    }
                                },
                        )
                    }
                }

                is TaskDetailUiEvent.SubtaskRemoved -> {
                    val subtaskId = event.subtaskId
                    mutate { task -> task.copy(subtasks = task.subtasks.filterNot { it.id == subtaskId }) }
                }

                is TaskDetailUiEvent.ReminderAdded -> {
                    val trigger = event.preset.trigger
                    mutate { task ->
                        task.copy(
                            reminders =
                                task.reminders +
                                    Reminder(id = UUID.randomUUID().toString(), taskId = task.id, trigger = trigger),
                        )
                    }
                }

                is TaskDetailUiEvent.ReminderRemoved -> {
                    val reminderId = event.reminderId
                    mutate { task -> task.copy(reminders = task.reminders.filterNot { it.id == reminderId }) }
                }

                is TaskDetailUiEvent.RecurrenceChanged -> {
                    val recurrence = event.recurrence
                    mutate { it.copy(recurrence = recurrence) }
                }

                TaskDetailUiEvent.StartStudySessionRequested -> {
                    currentTask()?.let { task ->
                        emitEffect(TaskDetailUiEffect.StartStudySession(task.id, task.subjectId))
                    }
                }
            }
        }

        private fun load(taskId: String) {
            savedStateHandle[TASK_ID_KEY] = taskId
            this.taskId.value = taskId
        }

        private fun addSubtask() {
            val title = newSubtaskTitle.value.trim()
            if (title.isBlank()) return
            mutate { task ->
                task.copy(
                    subtasks =
                        task.subtasks + Subtask(id = UUID.randomUUID().toString(), title = title),
                )
            }
            newSubtaskTitle.value = ""
        }

        private fun currentTask(): StudyTask? = (loadResult.value as? TaskLoadResult.Loaded)?.task

        private fun mutate(transform: (StudyTask) -> StudyTask) {
            val current = currentTask() ?: return
            val updated = transform(current).copy(updatedAt = clock.now())
            viewModelScope.launch { repository.save(updated) }
        }

        private companion object {
            const val TASK_ID_KEY = "taskDetail.taskId"
        }
    }

/** Distinguishes "no snapshot yet" from "the snapshot is `null`", which a bare `StudyTask?` cannot. */
private sealed interface TaskLoadResult {
    data object Idle : TaskLoadResult

    data class Loaded(
        val task: StudyTask?,
    ) : TaskLoadResult
}
