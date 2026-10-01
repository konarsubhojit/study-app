package dev.studyflow.feature.settings

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.studyflow.core.datastore.UploadNetwork
import dev.studyflow.core.datastore.UploadNetworkSettings
import dev.studyflow.core.domain.sync.SyncStatus
import dev.studyflow.core.domain.sync.SyncStatusRepository
import dev.studyflow.core.domain.sync.SyncTrigger
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("SyncStatusViewModel")
class SyncStatusViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val statusRepository = FakeSyncStatusRepository()
    private val requestedTriggers = mutableListOf<SyncTrigger>()

    @Test
    fun `the screen shows what is waiting, when sync last worked, and what went wrong`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            statusRepository.status.value =
                SyncStatus(pendingCount = 3, lastSuccessAt = LAST_SUCCESS, lastError = "server is down")
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(3, state.pendingCount)
                assertEquals(LAST_SUCCESS, state.lastSuccessAt)
                assertEquals("server is down", state.lastError)
                assertFalse(state.isUpToDate, "a device with unsent changes is not up to date")
            }
        }

    @Test
    fun `an empty queue with no error reads as up to date`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            statusRepository.status.value = SyncStatus(pendingCount = 0, lastSuccessAt = LAST_SUCCESS)
            advanceUntilIdle()

            viewModel.state.test { assertTrue(awaitItem().isUpToDate) }
        }

    @Test
    fun `changes waiting with no recorded attempt read as sync never having run`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            statusRepository.status.value = SyncStatus(pendingCount = 2)
            advanceUntilIdle()

            viewModel.state.test {
                assertTrue(
                    awaitItem().hasNeverRun,
                    "a queue no run has ever touched must not look like one waiting its turn",
                )
            }
        }

    @Test
    fun `any recorded attempt, or nothing to send, is not a sync that never ran`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.state.test {
                assertFalse(awaitItem().hasNeverRun, "an empty queue has nothing a run could have sent")

                statusRepository.status.value =
                    SyncStatus(pendingCount = 2, lastError = "server is down", lastAttemptAt = LAST_SUCCESS)
                assertFalse(awaitItem().hasNeverRun, "a failed attempt is a run, and says so on its own")
            }
        }

    @Test
    fun `sync now asks for a run the user is waiting on`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(SyncStatusUiEvent.SyncNow)
            advanceUntilIdle()

            // MANUAL rather than SCHEDULED: the trigger is what makes the run replace a pending
            // one and pull even when nothing local is waiting to be sent.
            assertEquals(listOf(SyncTrigger.MANUAL), requestedTriggers)
        }

    @Test
    fun `allowing mobile data saves the choice and starts the waiting uploads`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()
            viewModel.state.test {
                awaitItem()

                viewModel.onEvent(SyncStatusUiEvent.SetUploadNetwork(UploadNetwork.ANY_NETWORK))
                advanceUntilIdle()

                assertEquals(UploadNetwork.ANY_NETWORK, expectMostRecentItem().uploadNetwork)
            }
            // Changing the setting is only half an answer: the queue that was parked behind it has
            // to be released now, not at the next scheduled pass.
            assertEquals(listOf(SyncTrigger.MANUAL), requestedTriggers)
        }

    private fun viewModel(): SyncStatusViewModel =
        SyncStatusViewModel(
            savedStateHandle = SavedStateHandle(),
            statusRepository = statusRepository,
            syncScheduler = { trigger -> requestedTriggers += trigger },
            uploadNetworkSettings = uploadNetworkSettings,
        )

    private val uploadNetworkSettings = FakeUploadNetworkSettings()

    private class FakeUploadNetworkSettings : UploadNetworkSettings {
        val selected = MutableStateFlow(UploadNetwork.WIFI_ONLY)

        override val network: Flow<UploadNetwork> = selected

        override suspend fun setNetwork(network: UploadNetwork) {
            selected.value = network
        }
    }

    private class FakeSyncStatusRepository : SyncStatusRepository {
        val status = MutableStateFlow(SyncStatus())

        override fun observeStatus(): Flow<SyncStatus> = status
    }

    private companion object {
        val LAST_SUCCESS: Instant = Instant.parse("2026-03-01T09:00:00Z")
    }
}
