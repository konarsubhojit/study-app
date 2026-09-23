package dev.studyflow.feature.settings

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.domain.lifecycle.AccountDeletionCoordinator
import dev.studyflow.core.domain.lifecycle.DataExporter
import dev.studyflow.core.domain.lifecycle.DataImporter
import dev.studyflow.core.domain.lifecycle.SessionWithLog
import dev.studyflow.core.domain.result.DomainError
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import dev.studyflow.core.testing.coroutines.testDispatcherProvider
import dev.studyflow.core.testing.data.FakeArchiveStorage
import dev.studyflow.core.testing.data.FakeLocalDataStore
import dev.studyflow.core.testing.data.FakeMaterialFileStore
import dev.studyflow.core.testing.data.FakeRemoteAccountEraser
import dev.studyflow.core.testing.data.RecordingDataEraser
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK
import dev.studyflow.core.testing.data.testSessionEvent
import dev.studyflow.core.testing.data.testStudySession
import dev.studyflow.core.testing.data.testStudyTask
import dev.studyflow.core.testing.data.testSubject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("DataPrivacyViewModel")
class DataPrivacyViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val storage = FakeArchiveStorage()
    private val localData = FakeLocalDataStore()
    private val materialFiles = FakeMaterialFileStore()
    private val remote = FakeRemoteAccountEraser()
    private val database = RecordingDataEraser("database")
    private val credentials = RecordingDataEraser("credentials")

    @Test
    fun `exporting asks the user where the archive goes before it writes anything`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.effects.test {
                viewModel.onEvent(DataPrivacyUiEvent.ExportRequested)

                assertEquals(DataPrivacyUiEffect.CreateArchiveDocument(SUGGESTED_NAME), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(storage.archives.isEmpty(), "no archive is written until a destination exists")
        }

    @Test
    fun `the archive is written once the user has picked a destination`() =
        runTest(mainDispatcher.dispatcher) {
            givenAStudentsData()
            val viewModel = viewModel()

            viewModel.onEvent(DataPrivacyUiEvent.ExportDestinationChosen(DESTINATION))
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isBusy)
                assertEquals(
                    DataPrivacyMessage.Exported(records = 3, materialFiles = 0, materialsWithoutBytes = 0),
                    state.message,
                )
            }
            assertTrue(storage.archives.containsKey(DESTINATION))
        }

    @Test
    fun `backing out of the picker leaves the screen exactly as it was`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(DataPrivacyUiEvent.ExportDestinationChosen(uri = null))
            viewModel.onEvent(DataPrivacyUiEvent.ImportSourceChosen(uri = null))
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isBusy)
                assertNull(state.message)
            }
            assertTrue(storage.archives.isEmpty())
        }

    @Test
    fun `an import reports what it merged rather than claiming everything was new`() =
        runTest(mainDispatcher.dispatcher) {
            givenAStudentsData()
            val viewModel = viewModel()
            viewModel.onEvent(DataPrivacyUiEvent.ExportDestinationChosen(DESTINATION))
            advanceUntilIdle()

            viewModel.onEvent(DataPrivacyUiEvent.ImportSourceChosen(DESTINATION))
            advanceUntilIdle()

            viewModel.state.test {
                val message = awaitItem().message
                assertTrue(message is DataPrivacyMessage.Imported, "expected an import summary but got $message")
                assertEquals(3, (message as DataPrivacyMessage.Imported).unchanged)
                assertEquals(0, message.added)
            }
        }

    @Test
    fun `a failing import is reported as a failure and not as an empty success`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(DataPrivacyUiEvent.ImportSourceChosen("content://nothing/here.zip"))
            advanceUntilIdle()

            viewModel.state.test {
                assertEquals(
                    DataPrivacyMessage.Failed(DataPrivacyTask.IMPORT, DomainError.Network),
                    awaitItem().message,
                )
            }
        }

    @Test
    fun `a second action is refused while one is still running`() =
        runTest(mainDispatcher.dispatcher) {
            givenAStudentsData()
            val viewModel = viewModel()

            viewModel.effects.test {
                viewModel.onEvent(DataPrivacyUiEvent.ExportDestinationChosen(DESTINATION))
                viewModel.onEvent(DataPrivacyUiEvent.ImportRequested)

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            advanceUntilIdle()
            assertTrue(localData.applied.isEmpty(), "the import must not have started")
        }

    @Test
    fun `nothing is deleted until the user has confirmed`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(DataPrivacyUiEvent.DeleteAccountRequested)
            advanceUntilIdle()

            viewModel.state.test {
                assertTrue(awaitItem().confirmingDeletion, "the confirmation step is part of the flow, not decoration")
            }
            assertEquals(0, remote.calls)
            assertEquals(0, database.erasures)
        }

    @Test
    fun `dismissing the confirmation deletes nothing`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(DataPrivacyUiEvent.DeleteAccountRequested)
            viewModel.onEvent(DataPrivacyUiEvent.DeleteAccountDismissed)
            advanceUntilIdle()

            viewModel.state.test {
                assertFalse(awaitItem().confirmingDeletion)
            }
            assertEquals(0, remote.calls)
            assertEquals(0, credentials.erasures)
        }

    @Test
    fun `a confirmed deletion clears the device and reports the retention window`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(DataPrivacyUiEvent.DeleteAccountRequested)
            viewModel.onEvent(DataPrivacyUiEvent.DeleteAccountConfirmed)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.confirmingDeletion)
                assertEquals(
                    DataPrivacyMessage.AccountDeleted(retentionWindowDays = 30, remaining = emptyList()),
                    state.message,
                )
            }
            assertEquals(1, remote.calls)
            assertEquals(1, database.erasures)
            assertEquals(1, credentials.erasures)
        }

    @Test
    fun `a server that refuses leaves the device untouched and says so`() =
        runTest(mainDispatcher.dispatcher) {
            remote.failWith(DomainError.Network)
            val viewModel = viewModel()

            viewModel.onEvent(DataPrivacyUiEvent.DeleteAccountRequested)
            viewModel.onEvent(DataPrivacyUiEvent.DeleteAccountConfirmed)
            advanceUntilIdle()

            viewModel.state.test {
                assertEquals(
                    DataPrivacyMessage.Failed(DataPrivacyTask.DELETE, DomainError.Network),
                    awaitItem().message,
                )
            }
            assertEquals(0, database.erasures, "local data must survive a deletion the server never accepted")
        }

    @Test
    fun `a dismissed message goes away`() =
        runTest(mainDispatcher.dispatcher) {
            givenAStudentsData()
            val viewModel = viewModel()
            viewModel.onEvent(DataPrivacyUiEvent.ExportDestinationChosen(DESTINATION))
            advanceUntilIdle()

            viewModel.onEvent(DataPrivacyUiEvent.MessageDismissed)
            advanceUntilIdle()

            viewModel.state.test {
                assertNull(awaitItem().message)
            }
        }

    private fun givenAStudentsData() {
        localData.subjects += testSubject()
        localData.sessions += SessionWithLog(testStudySession(), listOf(testSessionEvent(sequence = 0)))
        localData.tasks += testStudyTask()
    }

    private fun TestScope.viewModel() =
        DataPrivacyViewModel(
            savedStateHandle = SavedStateHandle(),
            exporter =
                DataExporter(
                    localData = localData,
                    materialFiles = materialFiles,
                    sinkFactory = storage.sinkFactory,
                    clock = Clock { TEST_WALL_CLOCK },
                    dispatcherProvider = testDispatcherProvider(),
                ),
            importer =
                DataImporter(
                    sourceFactory = storage.sourceFactory,
                    localData = localData,
                    writer = localData,
                    materialFiles = materialFiles,
                    dispatcherProvider = testDispatcherProvider(),
                ),
            deletionCoordinator =
                AccountDeletionCoordinator(
                    remote = remote,
                    erasers = listOf(database, credentials),
                    dispatcherProvider = testDispatcherProvider(),
                ),
            archiveNaming = { SUGGESTED_NAME },
        )

    private companion object {
        const val DESTINATION = "content://downloads/studyflow-export.zip"
        const val SUGGESTED_NAME = "studyflow-export-2026-01-01.zip"
    }
}
