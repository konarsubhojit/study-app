package dev.studyflow.feature.settings

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
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
    fun `sync now asks for a run the user is waiting on`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(SyncStatusUiEvent.SyncNow)
            advanceUntilIdle()

            // MANUAL rather than SCHEDULED: the trigger is what makes the run replace a pending
            // one and pull even when nothing local is waiting to be sent.
            assertEquals(listOf(SyncTrigger.MANUAL), requestedTriggers)
        }

    private fun viewModel(): SyncStatusViewModel =
        SyncStatusViewModel(
            savedStateHandle = SavedStateHandle(),
            statusRepository = statusRepository,
            syncScheduler = { trigger -> requestedTriggers += trigger },
        )

    private class FakeSyncStatusRepository : SyncStatusRepository {
        val status = MutableStateFlow(SyncStatus())

        override fun observeStatus(): Flow<SyncStatus> = status
    }

    private companion object {
        val LAST_SUCCESS: Instant = Instant.parse("2026-03-01T09:00:00Z")
    }
}
