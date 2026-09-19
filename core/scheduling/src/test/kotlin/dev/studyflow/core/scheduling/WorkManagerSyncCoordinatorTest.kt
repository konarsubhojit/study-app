package dev.studyflow.core.scheduling

import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.studyflow.core.domain.session.SessionCommandResult
import dev.studyflow.core.domain.sync.SyncTrigger
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * What the platform guarantees about a drain: when it may run, and how many there are.
 *
 * These are the properties that make offline-first work without the app being open, so they are
 * asserted against a real `WorkManager` rather than trusted to the builder call that sets them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkManagerSyncCoordinatorTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var workManager: WorkManager
    private lateinit var coordinator: WorkManagerSyncCoordinator

    @Before
    fun setUp() {
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
        coordinator = WorkManagerSyncCoordinator(context, workManager)
    }

    @Test
    fun `a drain waits for a connected network instead of failing offline`() {
        coordinator.enqueue(SyncTrigger.OUTBOUND)

        val info = workManager.getWorkInfosForUniqueWork(SYNC_WORK_NAME).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test
    fun `a burst of local mutations still enqueues a single drain`() {
        coordinator.enqueue(SyncTrigger.OUTBOUND)
        val first = workManager.idOfSyncWork()

        coordinator.enqueue(SyncTrigger.OUTBOUND)
        coordinator.enqueue(SyncTrigger.OUTBOUND)

        // KEEP, so three stopped sessions in a row do not restart a run that is already coming.
        assertEquals(1, workManager.getWorkInfosForUniqueWork(SYNC_WORK_NAME).get().size)
        assertEquals(first, workManager.idOfSyncWork())
    }

    @Test
    fun `sync now replaces the run that was waiting on its backoff`() {
        coordinator.enqueue(SyncTrigger.SCHEDULED)
        val scheduled = workManager.idOfSyncWork()

        coordinator.enqueue(SyncTrigger.MANUAL)

        // REPLACE, because a user who taps "Sync now" is asking for a run now rather than for
        // their tap to be swallowed by the one already waiting.
        assertNotEquals(scheduled, workManager.idOfSyncWork())
    }

    @Test
    fun `the recurring catch-up run is scheduled once and also waits for a network`() {
        coordinator.ensureScheduled()
        coordinator.ensureScheduled()

        // KEEP: called on every cold start, and re-enqueueing would reset the cadence's anchor
        // each time the user opens the app.
        val info = workManager.getWorkInfosForUniqueWork(PERIODIC_SYNC_WORK_NAME).get().single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
    }

    @Test
    fun `stopping a session asks for a drain and running one does not`() {
        val observer = SyncOnSessionCommandObserver(coordinator)

        observer.onSessionCommandApplied(SessionCommandResult.Applied(session(SessionStatus.RUNNING), TimerState.Idle))
        assertTrue(workManager.getWorkInfosForUniqueWork(SYNC_WORK_NAME).get().isEmpty())

        observer.onSessionCommandApplied(SessionCommandResult.Applied(session(SessionStatus.STOPPED), TimerState.Idle))
        assertEquals(1, workManager.getWorkInfosForUniqueWork(SYNC_WORK_NAME).get().size)
    }

    private fun WorkManager.idOfSyncWork() =
        getWorkInfosForUniqueWork(SYNC_WORK_NAME).get().single { it.state != WorkInfo.State.CANCELLED }.id

    private fun session(status: SessionStatus): StudySession =
        StudySession(
            id = "session-1",
            subjectId = null,
            note = null,
            startedAt = NOW,
            endedAt = if (status == SessionStatus.STOPPED) NOW else null,
            status = status,
            elapsed = SessionElapsed(counted = Duration.ZERO),
            deviceId = "device-a",
            updatedAt = NOW,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-01T09:00:00Z")
    }
}
