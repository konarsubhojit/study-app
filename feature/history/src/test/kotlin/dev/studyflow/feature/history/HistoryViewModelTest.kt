package dev.studyflow.feature.history

import androidx.lifecycle.SavedStateHandle
import dev.studyflow.core.common.time.DeviceIdProvider
import dev.studyflow.core.domain.result.UserMessage
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import dev.studyflow.core.testing.data.FakeSessionHistoryRepository
import dev.studyflow.core.testing.data.FakeSubjectRepository
import dev.studyflow.core.testing.data.testStudySession
import dev.studyflow.core.testing.data.testSubject
import dev.studyflow.core.testing.time.FakeDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("HistoryViewModel")
class HistoryViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val device = FakeDevice()
    private val subjectRepository = FakeSubjectRepository().apply { put(testSubject(id = "subject-1")) }
    private val deviceIdProvider = DeviceIdProvider { "device-under-test" }

    @Test
    fun `editing a session's note appends a correction and the change is visible immediately`() =
        runTest(mainDispatcher.dispatcher) {
            val session =
                testStudySession(
                    id = "session-1",
                    startedAt = device.now() - 30.minutes,
                    endedAt = device.now(),
                ).copy(manualOverride = false)
            val repository = FakeSessionHistoryRepository(seed = listOf(stopped(session)))
            val viewModel = viewModel(repository)

            viewModel.onEvent(HistoryUiEvent.SessionOpened(stopped(session)))
            viewModel.onEvent(HistoryUiEvent.EditNoteChanged("Reviewed chapter 3"))
            viewModel.onEvent(HistoryUiEvent.EditConfirmed)
            advanceUntilIdle()

            assertEquals(1, repository.corrections.size)
            assertNull(viewModel.state.value.dialog)
        }

    @Test
    fun `deleting a session offers undo, and undo restores it`() =
        runTest(mainDispatcher.dispatcher) {
            val session = stopped(testStudySession(id = "session-1"))
            val repository = FakeSessionHistoryRepository(seed = listOf(session))
            val viewModel = viewModel(repository)

            viewModel.onEvent(HistoryUiEvent.DeleteRequested("session-1"))
            advanceUntilIdle()

            assertEquals(HistoryUndo(listOf("session-1")), viewModel.state.value.undo)

            viewModel.onEvent(HistoryUiEvent.UndoRequested)
            advanceUntilIdle()

            assertNull(viewModel.state.value.undo)
            assertEquals(2, repository.corrections.size)
        }

    @Test
    fun `splitting a stopped session at its midpoint produces two sessions`() =
        runTest(mainDispatcher.dispatcher) {
            val session =
                stopped(
                    testStudySession(
                        id = "session-1",
                        startedAt = device.now(),
                        endedAt = device.now() + 60.minutes,
                    ),
                )
            val repository = FakeSessionHistoryRepository(seed = listOf(session))
            val viewModel = viewModel(repository)

            viewModel.onEvent(HistoryUiEvent.SplitRequested(session))
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.dialog as? HistoryDialog.Split)

            viewModel.onEvent(HistoryUiEvent.SplitConfirmed)
            advanceUntilIdle()

            assertNull(viewModel.state.value.dialog)
            assertEquals(1, repository.corrections.size)
        }

    @Test
    fun `merge is only offered once two or more sessions are selected`() =
        runTest(mainDispatcher.dispatcher) {
            val first = stopped(testStudySession(id = "session-1", subjectId = "subject-1"))
            val second =
                stopped(
                    testStudySession(
                        id = "session-2",
                        subjectId = "subject-1",
                        startedAt = first.endedAt!!,
                        endedAt = first.endedAt!! + 30.minutes,
                    ),
                )
            val repository = FakeSessionHistoryRepository(seed = listOf(first, second))
            val viewModel = viewModel(repository)

            viewModel.onEvent(HistoryUiEvent.MergeRequested)
            advanceUntilIdle()
            assertNull(viewModel.state.value.dialog)

            viewModel.onEvent(HistoryUiEvent.SelectionToggled("session-1"))
            viewModel.onEvent(HistoryUiEvent.SelectionToggled("session-2"))
            viewModel.onEvent(HistoryUiEvent.MergeRequested)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.dialog as? HistoryDialog.Merge)

            viewModel.onEvent(HistoryUiEvent.MergeConfirmed)
            advanceUntilIdle()

            assertNull(viewModel.state.value.dialog)
            assertTrue(
                viewModel.state.value.selectedIds
                    .isEmpty(),
            )
        }

    @Test
    fun `manual entry saves a new stopped session`() =
        runTest(mainDispatcher.dispatcher) {
            val repository = FakeSessionHistoryRepository()
            val viewModel = viewModel(repository)

            viewModel.onEvent(HistoryUiEvent.ManualEntryRequested)
            viewModel.onEvent(HistoryUiEvent.ManualEntrySubjectChanged("subject-1"))
            viewModel.onEvent(HistoryUiEvent.ManualEntryNoteChanged("Offline library session"))
            viewModel.onEvent(HistoryUiEvent.ManualEntryConfirmed)
            advanceUntilIdle()

            assertNull(viewModel.state.value.dialog)
            assertEquals(1, repository.corrections.size)
        }

    @Test
    fun `manual entry failure keeps the form open and reports a safe error`() =
        runTest(mainDispatcher.dispatcher) {
            val repository = FakeSessionHistoryRepository().apply { failNext = true }
            val viewModel = viewModel(repository)

            viewModel.onEvent(HistoryUiEvent.ManualEntryRequested)
            viewModel.onEvent(HistoryUiEvent.ManualEntryConfirmed)
            advanceUntilIdle()

            assertNotNull(viewModel.state.value.dialog as? HistoryDialog.ManualEntry)
            assertEquals(UserMessage.Unknown, viewModel.state.value.errorMessage)
        }

    @Test
    fun `changing the date filter retains both bounds`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel(FakeSessionHistoryRepository())
            val from = device.now() - 60.minutes
            val to = device.now()

            viewModel.onEvent(HistoryUiEvent.DateRangeFilterChanged(from, to))
            advanceUntilIdle()

            assertEquals(from, viewModel.state.value.filter.from)
            assertEquals(to, viewModel.state.value.filter.to)
        }

    private fun stopped(session: dev.studyflow.core.model.StudySession) =
        session.copy(
            status = dev.studyflow.core.model.SessionStatus.STOPPED,
            endedAt = session.endedAt ?: (session.startedAt + 30.minutes),
            manualOverride = true,
        )

    private fun viewModel(repository: FakeSessionHistoryRepository): HistoryViewModel =
        HistoryViewModel(SavedStateHandle(), repository, subjectRepository, device, deviceIdProvider)
}
