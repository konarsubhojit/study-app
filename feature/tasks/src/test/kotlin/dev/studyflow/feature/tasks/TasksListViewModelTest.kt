package dev.studyflow.feature.tasks

import androidx.lifecycle.SavedStateHandle
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.RecurrenceRule
import dev.studyflow.core.model.TaskPriority
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import dev.studyflow.core.testing.data.FakeTaskRepository
import dev.studyflow.core.testing.data.testStudyTask
import dev.studyflow.core.testing.time.FakeDevice
import dev.studyflow.core.testing.time.FakeTimeZoneProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("TasksListViewModel")
class TasksListViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val device = FakeDevice()
    private val repository = FakeTaskRepository(now = device.now(), timeZone = device.currentTimeZone())

    @Test
    fun `quick-add resolves the today chip to the current local date`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(TasksListUiEvent.QuickAddTitleChanged("Finish reading"))
            viewModel.onEvent(TasksListUiEvent.QuickAddDueDateChanged(QuickAddDueDate.Today))
            viewModel.onEvent(TasksListUiEvent.QuickAddSubmitted)
            advanceUntilIdle()

            val saved = repository.observeTasks().first().single()
            assertEquals("Finish reading", saved.title)
            assertEquals(LocalDateTime(2026, 3, 1, 0, 0), saved.dueAt)
            assertTrue(saved.isAllDay)
        }

    @Test
    fun `quick-add resolves the tomorrow and next-week chips relative to today`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(TasksListUiEvent.QuickAddTitleChanged("Tomorrow task"))
            viewModel.onEvent(TasksListUiEvent.QuickAddDueDateChanged(QuickAddDueDate.Tomorrow))
            viewModel.onEvent(TasksListUiEvent.QuickAddSubmitted)
            viewModel.onEvent(TasksListUiEvent.QuickAddTitleChanged("Next week task"))
            viewModel.onEvent(TasksListUiEvent.QuickAddDueDateChanged(QuickAddDueDate.NextWeek))
            viewModel.onEvent(TasksListUiEvent.QuickAddSubmitted)
            advanceUntilIdle()

            val tasks = repository.observeTasks().first()
            assertEquals(LocalDateTime(2026, 3, 2, 0, 0), tasks.first { it.title == "Tomorrow task" }.dueAt)
            assertEquals(LocalDateTime(2026, 3, 8, 0, 0), tasks.first { it.title == "Next week task" }.dueAt)
        }

    @Test
    fun `quick-add with no due date chip creates an undated task and clears the draft`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(TasksListUiEvent.QuickAddTitleChanged("Someday task"))
            viewModel.onEvent(TasksListUiEvent.QuickAddSubmitted)
            advanceUntilIdle()

            assertEquals("", viewModel.state.value.quickAddTitle)
            assertNull(
                repository
                    .observeTasks()
                    .first()
                    .single()
                    .dueAt,
            )
        }

    @Test
    fun `blank quick-add titles are ignored`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(TasksListUiEvent.QuickAddTitleChanged("   "))
            viewModel.onEvent(TasksListUiEvent.QuickAddSubmitted)
            advanceUntilIdle()

            assertTrue(viewModel.state.value.hasNoTasksWhatsoever)
        }

    @Test
    fun `tasks group into overdue, today, upcoming and someday sections`() =
        runTest(mainDispatcher.dispatcher) {
            val overdue = testStudyTask(id = "overdue", dueAt = LocalDateTime(2026, 2, 20, 9, 0))
            val today = testStudyTask(id = "today", dueAt = LocalDateTime(2026, 3, 1, 12, 0))
            val upcoming = testStudyTask(id = "upcoming", dueAt = LocalDateTime(2026, 3, 10, 9, 0))
            val someday = testStudyTask(id = "someday", dueAt = null)
            listOf(overdue, today, upcoming, someday).forEach { repository.save(it) }

            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(listOf("overdue"), state.overdue.map { it.id })
            assertEquals(listOf("today"), state.today.map { it.id })
            assertEquals(listOf("upcoming"), state.upcoming.map { it.id })
            assertEquals(listOf("someday"), state.someday.map { it.id })
        }

    @Test
    fun `a search query narrows every section at once`() =
        runTest(mainDispatcher.dispatcher) {
            val matching =
                testStudyTask(id = "matching", title = "Physics revision", dueAt = LocalDateTime(2026, 3, 1, 9, 0))
            val other = testStudyTask(id = "other", title = "Chemistry lab", dueAt = LocalDateTime(2026, 3, 1, 10, 0))
            listOf(matching, other).forEach { repository.save(it) }

            val viewModel = viewModel()
            viewModel.onEvent(TasksListUiEvent.QueryChanged("physics"))
            advanceUntilIdle()

            assertEquals(
                listOf("matching"),
                viewModel.state.value.today
                    .map { it.id },
            )
        }

    @Test
    fun `a priority filter narrows sections to that priority`() =
        runTest(mainDispatcher.dispatcher) {
            val high = testStudyTask(id = "high", priority = TaskPriority.HIGH, dueAt = LocalDateTime(2026, 3, 1, 9, 0))
            val normal = testStudyTask(id = "normal", dueAt = LocalDateTime(2026, 3, 1, 10, 0))
            listOf(high, normal).forEach { repository.save(it) }

            val viewModel = viewModel()
            viewModel.onEvent(TasksListUiEvent.PriorityFilterChanged(TaskPriority.HIGH))
            advanceUntilIdle()

            assertEquals(
                listOf("high"),
                viewModel.state.value.today
                    .map { it.id },
            )
        }

    @Test
    fun `completing a task through the checkbox or swipe can be undone`() =
        runTest(mainDispatcher.dispatcher) {
            val task = testStudyTask(id = "task-1", dueAt = LocalDateTime(2026, 3, 1, 9, 0))
            repository.save(task)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onEvent(TasksListUiEvent.TaskCompletionToggled(task))
            advanceUntilIdle()
            assertTrue(repository.observeTask("task-1").first()!!.isCompleted)
            assertEquals(TaskListUndo.Completed(task), viewModel.state.value.undo)

            viewModel.onEvent(TasksListUiEvent.UndoRequested)
            advanceUntilIdle()
            assertFalse(repository.observeTask("task-1").first()!!.isCompleted)
            assertNull(viewModel.state.value.undo)
        }

    @Test
    fun `completing a recurring task advances it to the next occurrence`() =
        runTest(mainDispatcher.dispatcher) {
            val task =
                testStudyTask(
                    id = "task-1",
                    dueAt = LocalDateTime(2026, 3, 1, 9, 0),
                    recurrence = RecurrenceRule(RecurrenceFrequency.DAILY),
                )
            repository.save(task)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onEvent(TasksListUiEvent.TaskCompletionToggled(task))
            advanceUntilIdle()

            val saved = repository.observeTask("task-1").first()!!
            assertEquals(LocalDateTime(2026, 3, 2, 9, 0), saved.dueAt)
            assertFalse(saved.isCompleted)
            assertEquals(TaskListUndo.Completed(task), viewModel.state.value.undo)
        }

    @Test
    fun `snoozing a task pushes its due date out a day and can be undone`() =
        runTest(mainDispatcher.dispatcher) {
            val task = testStudyTask(id = "task-1", dueAt = LocalDateTime(2026, 3, 1, 9, 0))
            repository.save(task)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onEvent(TasksListUiEvent.TaskSnoozed(task))
            advanceUntilIdle()
            assertEquals(LocalDateTime(2026, 3, 2, 9, 0), repository.observeTask("task-1").first()?.dueAt)
            assertEquals(TaskListUndo.Snoozed(task), viewModel.state.value.undo)

            viewModel.onEvent(TasksListUiEvent.UndoRequested)
            advanceUntilIdle()
            assertEquals(LocalDateTime(2026, 3, 1, 9, 0), repository.observeTask("task-1").first()?.dueAt)
        }

    private fun viewModel(): TasksListViewModel =
        TasksListViewModel(SavedStateHandle(), repository, device, FakeTimeZoneProvider(device))
}
