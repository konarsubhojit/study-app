package dev.studyflow.feature.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.domain.tasks.TaskRepository
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.TaskPriority
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.util.UUID
import javax.inject.Inject

/**
 * The four sections a task list groups into (issue #46).
 *
 * Overdue, today and upcoming come straight from [TaskRepository]'s dedicated flows; "someday"
 * (open, undated) is the one window the repository has no index for, since it is everything the
 * other three are not rather than a boundary SQL can plan on.
 */
public data class TasksListUiState(
    val loading: Boolean = true,
    val overdue: List<StudyTask> = emptyList(),
    val today: List<StudyTask> = emptyList(),
    val upcoming: List<StudyTask> = emptyList(),
    val someday: List<StudyTask> = emptyList(),
    val filter: TaskListFilter = TaskListFilter(),
    val filterOptions: TaskFilterOptions = TaskFilterOptions(),
    val quickAddTitle: String = "",
    val quickAddDueDate: QuickAddDueDate = QuickAddDueDate.None,
    val undo: TaskListUndo? = null,
) : UiState {
    /** True once every section has been loaded and none has a task to show, filters included. */
    public val isEmpty: Boolean
        get() = !loading && overdue.isEmpty() && today.isEmpty() && upcoming.isEmpty() && someday.isEmpty()

    /** True when there are no tasks at all, before any filter is considered — the first-run case. */
    public val hasNoTasksWhatsoever: Boolean
        get() = isEmpty && !filter.isNarrowed
}

/** A completion or snooze the user can still take back, offered as an undo snackbar. */
public sealed interface TaskListUndo {
    /** The task exactly as it was before the action, so undo is a plain save. */
    public val original: StudyTask

    public data class Completed(
        override val original: StudyTask,
    ) : TaskListUndo

    public data class Snoozed(
        override val original: StudyTask,
    ) : TaskListUndo
}

public sealed interface TasksListUiEvent : UiEvent {
    public data class QueryChanged(
        val query: String,
    ) : TasksListUiEvent

    public data class SubjectFilterChanged(
        val subjectId: String?,
    ) : TasksListUiEvent

    public data class TagFilterChanged(
        val tag: String?,
    ) : TasksListUiEvent

    public data class PriorityFilterChanged(
        val priority: TaskPriority?,
    ) : TasksListUiEvent

    public data class SortChanged(
        val sort: TaskSort,
    ) : TasksListUiEvent

    public data class QuickAddTitleChanged(
        val title: String,
    ) : TasksListUiEvent

    public data class QuickAddDueDateChanged(
        val dueDate: QuickAddDueDate,
    ) : TasksListUiEvent

    /** Submits the pending quick-add title, the second of the two interactions issue #46 asks for. */
    public data object QuickAddSubmitted : TasksListUiEvent

    /** The checkbox or the swipe-to-complete gesture; both dispatch the same event. */
    public data class TaskCompletionToggled(
        val task: StudyTask,
    ) : TasksListUiEvent

    /** The menu action or the swipe-to-snooze gesture; both push the due date out a day. */
    public data class TaskSnoozed(
        val task: StudyTask,
    ) : TasksListUiEvent

    public data object UndoRequested : TasksListUiEvent

    public data object UndoDismissed : TasksListUiEvent

    public data class TaskOpened(
        val taskId: String,
    ) : TasksListUiEvent
}

public sealed interface TasksListUiEffect : UiEffect {
    public data class NavigateToTaskDetail(
        val taskId: String,
    ) : TasksListUiEffect
}

/**
 * Drives the task list: the four grouped sections, the quick-add form and the filter/sort state.
 *
 * Nothing here derives "today" from a stored boundary; every section is re-collected from
 * [TaskRepository] so the list is correct across midnight without a manual refresh.
 */
@HiltViewModel
public class TasksListViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: TaskRepository,
        private val clock: Clock,
        private val timeZoneProvider: TimeZoneProvider,
    ) : MviViewModel<TasksListUiEvent, TasksListUiEffect>(savedStateHandle) {
        private val filter = MutableStateFlow(TaskListFilter())
        private val quickAddTitle = MutableStateFlow("")
        private val quickAddDueDate = MutableStateFlow<QuickAddDueDate>(QuickAddDueDate.None)
        private val undo = MutableStateFlow<TaskListUndo?>(null)

        private val filterOptions: StateFlow<TaskFilterOptions> =
            repository
                .observeTasks()
                .map { it.filterOptions() }
                .stateInViewModel(TaskFilterOptions())

        private val allAndOptions: Flow<Pair<List<StudyTask>, TaskFilterOptions>> =
            combine(repository.observeTasks(), filterOptions) { all, options -> all to options }

        private val sections: StateFlow<TasksListSections> =
            combine(
                repository.observeOverdue(),
                repository.observeToday(),
                repository.observeUpcoming(),
                allAndOptions,
                filter,
            ) { overdue, today, upcoming, (all, currentOptions), currentFilter ->
                TasksListSections(
                    overdue = overdue.applyTo(currentFilter),
                    today = today.applyTo(currentFilter),
                    upcoming = upcoming.applyTo(currentFilter),
                    someday = all.someday().applyTo(currentFilter),
                    filterOptions = currentOptions,
                )
            }.stateInViewModel(TasksListSections())

        public val state: StateFlow<TasksListUiState> =
            combine(filter, sections, quickAddTitle, quickAddDueDate, undo) {
                currentFilter,
                currentSections,
                title,
                dueDate,
                pendingUndo,
                ->
                TasksListUiState(
                    loading = false,
                    overdue = currentSections.overdue,
                    today = currentSections.today,
                    upcoming = currentSections.upcoming,
                    someday = currentSections.someday,
                    filter = currentFilter,
                    filterOptions = currentSections.filterOptions,
                    quickAddTitle = title,
                    quickAddDueDate = dueDate,
                    undo = pendingUndo,
                )
            }.stateInViewModel(TasksListUiState())

        override fun onEvent(event: TasksListUiEvent) {
            when (event) {
                is TasksListUiEvent.QueryChanged -> {
                    filter.value = filter.value.copy(query = event.query)
                }

                is TasksListUiEvent.SubjectFilterChanged -> {
                    filter.value =
                        filter.value.copy(subjectId = event.subjectId)
                }

                is TasksListUiEvent.TagFilterChanged -> {
                    filter.value = filter.value.copy(tag = event.tag)
                }

                is TasksListUiEvent.PriorityFilterChanged -> {
                    filter.value = filter.value.copy(priority = event.priority)
                }

                is TasksListUiEvent.SortChanged -> {
                    filter.value = filter.value.copy(sort = event.sort)
                }

                is TasksListUiEvent.QuickAddTitleChanged -> {
                    quickAddTitle.value = event.title
                }

                is TasksListUiEvent.QuickAddDueDateChanged -> {
                    quickAddDueDate.value = event.dueDate
                }

                TasksListUiEvent.QuickAddSubmitted -> {
                    submitQuickAdd()
                }

                is TasksListUiEvent.TaskCompletionToggled -> {
                    toggleCompletion(event.task)
                }

                is TasksListUiEvent.TaskSnoozed -> {
                    snooze(event.task)
                }

                TasksListUiEvent.UndoRequested -> {
                    undoLastAction()
                }

                TasksListUiEvent.UndoDismissed -> {
                    undo.value = null
                }

                is TasksListUiEvent.TaskOpened -> {
                    emitEffect(TasksListUiEffect.NavigateToTaskDetail(event.taskId))
                }
            }
        }

        private fun submitQuickAdd() {
            val title = quickAddTitle.value.trim()
            if (title.isBlank()) return
            val now = clock.now()
            val timeZone = timeZoneProvider.current()
            val dueDate = quickAddDueDate.value.resolve(now, timeZone)
            val task =
                StudyTask(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    dueAt = dueDate?.atAllDayMidnight(),
                    timeZone = timeZone,
                    isAllDay = dueDate != null,
                    updatedAt = now,
                )
            viewModelScope.launch { repository.save(task) }
            quickAddTitle.value = ""
            quickAddDueDate.value = QuickAddDueDate.None
        }

        private fun toggleCompletion(task: StudyTask) {
            val now = clock.now()
            val updated =
                if (task.isCompleted) {
                    task.copy(completedAt = null, updatedAt = now)
                } else {
                    task.copy(completedAt = now, updatedAt = now)
                }
            viewModelScope.launch { repository.save(updated) }
            // Undo only makes sense for the action that just closed the task, not for reopening one.
            undo.value = if (updated.isCompleted) TaskListUndo.Completed(original = task) else null
        }

        private fun snooze(task: StudyTask) {
            val now = clock.now()
            val zone = task.timeZone
            val nextDate = (task.dueAt?.date ?: now.toLocalDateTime(zone).date).plus(1, DateTimeUnit.DAY)
            val updatedDueAt =
                task.dueAt?.let { LocalDateTime(nextDate, it.time) } ?: nextDate.atAllDayMidnight()
            val updated =
                task.copy(
                    dueAt = updatedDueAt,
                    isAllDay = task.isAllDay || task.dueAt == null,
                    updatedAt = now,
                )
            viewModelScope.launch { repository.save(updated) }
            undo.value = TaskListUndo.Snoozed(original = task)
        }

        private fun undoLastAction() {
            val pending = undo.value ?: return
            viewModelScope.launch { repository.save(pending.original) }
            undo.value = null
        }
    }

/** The four grouped, filtered sections plus the options available to filter by, as one snapshot. */
private data class TasksListSections(
    val overdue: List<StudyTask> = emptyList(),
    val today: List<StudyTask> = emptyList(),
    val upcoming: List<StudyTask> = emptyList(),
    val someday: List<StudyTask> = emptyList(),
    val filterOptions: TaskFilterOptions = TaskFilterOptions(),
)
