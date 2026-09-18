package dev.studyflow.feature.tasks

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.RecurrenceRule
import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import dev.studyflow.core.testing.data.FakeSessionHistoryRepository
import dev.studyflow.core.testing.data.FakeTaskRepository
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK
import dev.studyflow.core.testing.data.testStudySession
import dev.studyflow.core.testing.data.testStudyTask
import dev.studyflow.core.testing.time.FakeDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("TaskDetailViewModel")
class TaskDetailViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val device = FakeDevice()
    private val repository = FakeTaskRepository(now = device.now(), timeZone = device.currentTimeZone())
    private val sessionHistoryRepository = FakeSessionHistoryRepository()

    @Test
    fun `loading an unknown task settles into not-found rather than staying loading forever`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(TaskDetailUiEvent.Load("missing"))
            advanceUntilIdle()

            assertTrue(viewModel.state.value.notFound)
        }

    @Test
    fun `editing notes round-trips through save`() =
        runTest(mainDispatcher.dispatcher) {
            repository.save(testStudyTask(id = "task-1", notes = null))
            val viewModel = viewModel()
            viewModel.onEvent(TaskDetailUiEvent.Load("task-1"))
            advanceUntilIdle()

            viewModel.onEvent(TaskDetailUiEvent.NotesChanged("Bring calculator"))
            advanceUntilIdle()

            assertEquals("Bring calculator", repository.observeTask("task-1").first()?.notes)
        }

    @Test
    fun `adding, toggling and removing a subtask round-trips through save`() =
        runTest(mainDispatcher.dispatcher) {
            repository.save(testStudyTask(id = "task-1"))
            val viewModel = viewModel()
            viewModel.onEvent(TaskDetailUiEvent.Load("task-1"))
            advanceUntilIdle()

            viewModel.onEvent(TaskDetailUiEvent.NewSubtaskTitleChanged("Read chapter 1"))
            viewModel.onEvent(TaskDetailUiEvent.SubtaskAdded)
            advanceUntilIdle()

            val added =
                repository
                    .observeTask("task-1")
                    .first()!!
                    .subtasks
                    .single()
            assertEquals("Read chapter 1", added.title)
            assertEquals("", viewModel.state.value.newSubtaskTitle)

            viewModel.onEvent(TaskDetailUiEvent.SubtaskToggled(added.id))
            advanceUntilIdle()
            assertTrue(
                repository
                    .observeTask("task-1")
                    .first()!!
                    .subtasks
                    .single()
                    .isCompleted,
            )

            viewModel.onEvent(TaskDetailUiEvent.SubtaskRemoved(added.id))
            advanceUntilIdle()
            assertTrue(
                repository
                    .observeTask("task-1")
                    .first()!!
                    .subtasks
                    .isEmpty(),
            )
        }

    @Test
    fun `adding and removing a reminder round-trips through save`() =
        runTest(mainDispatcher.dispatcher) {
            repository.save(testStudyTask(id = "task-1", dueAt = LocalDateTime(2026, 3, 1, 9, 0)))
            val viewModel = viewModel()
            viewModel.onEvent(TaskDetailUiEvent.Load("task-1"))
            advanceUntilIdle()

            viewModel.onEvent(TaskDetailUiEvent.ReminderAdded(ReminderPreset.ONE_HOUR_BEFORE))
            advanceUntilIdle()

            val reminder =
                repository
                    .observeTask("task-1")
                    .first()!!
                    .reminders
                    .single()

            viewModel.onEvent(TaskDetailUiEvent.ReminderRemoved(reminder.id))
            advanceUntilIdle()
            assertTrue(
                repository
                    .observeTask("task-1")
                    .first()!!
                    .reminders
                    .isEmpty(),
            )
        }

    @Test
    fun `changing the recurrence rule round-trips through save`() =
        runTest(mainDispatcher.dispatcher) {
            repository.save(testStudyTask(id = "task-1", dueAt = LocalDateTime(2026, 3, 1, 9, 0)))
            val viewModel = viewModel()
            viewModel.onEvent(TaskDetailUiEvent.Load("task-1"))
            advanceUntilIdle()

            val weekly = RecurrenceRule(frequency = RecurrenceFrequency.WEEKLY, interval = 1)
            viewModel.onEvent(TaskDetailUiEvent.RecurrenceChanged(weekly))
            advanceUntilIdle()

            assertEquals(weekly, repository.observeTask("task-1").first()?.recurrence)

            viewModel.onEvent(TaskDetailUiEvent.RecurrenceChanged(null))
            advanceUntilIdle()
            assertNull(repository.observeTask("task-1").first()?.recurrence)
        }

    @Test
    fun `requesting a study session emits a stub effect naming the task and subject`() =
        runTest(mainDispatcher.dispatcher) {
            repository.save(testStudyTask(id = "task-1", subjectId = "subject-1"))
            val viewModel = viewModel()
            viewModel.onEvent(TaskDetailUiEvent.Load("task-1"))
            advanceUntilIdle()

            viewModel.effects.test {
                viewModel.onEvent(TaskDetailUiEvent.StartStudySessionRequested)

                assertEquals(TaskDetailUiEffect.StartStudySession("task-1", "subject-1"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `study totals update when an attributed session timing is edited`() =
        runTest(mainDispatcher.dispatcher) {
            repository.save(testStudyTask(id = "task-1", subjectId = "subject-1"))
            val history =
                FakeSessionHistoryRepository(
                    seed =
                        listOf(
                            testStudySession(
                                id = "session-1",
                                taskId = "task-1",
                                subjectId = "subject-1",
                                endedAt = TEST_WALL_CLOCK + 30.minutes,
                                status = SessionStatus.STOPPED,
                                elapsed = SessionElapsed(30.minutes),
                            ),
                        ),
                )
            val viewModel = viewModel(history)
            viewModel.onEvent(TaskDetailUiEvent.Load("task-1"))
            advanceUntilIdle()

            assertEquals(30.minutes, viewModel.state.value.taskStudyTime)
            assertEquals(30.minutes, viewModel.state.value.subjectStudyTime)

            history.editTiming(
                sessionId = "session-1",
                startedAt = TEST_WALL_CLOCK,
                endedAt = TEST_WALL_CLOCK + 1.hours,
                correctionId = "correction-1",
                at = TEST_WALL_CLOCK + 2.hours,
            )
            advanceUntilIdle()

            assertEquals(1.hours, viewModel.state.value.taskStudyTime)
            assertEquals(1.hours, viewModel.state.value.subjectStudyTime)
        }

    private fun viewModel(
        historyRepository: FakeSessionHistoryRepository = sessionHistoryRepository,
    ): TaskDetailViewModel = TaskDetailViewModel(SavedStateHandle(), repository, historyRepository, device)
}
