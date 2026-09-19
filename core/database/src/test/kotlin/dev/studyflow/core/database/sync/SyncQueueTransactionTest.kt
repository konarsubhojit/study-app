package dev.studyflow.core.database.sync

import androidx.room.Room
import dev.studyflow.core.database.DATABASE_ROBOLECTRIC_SDK
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.entity.asExternalModel
import dev.studyflow.core.database.session.OfflineFirstSessionHistoryRepository
import dev.studyflow.core.database.session.OfflineFirstSessionRepository
import dev.studyflow.core.domain.session.SessionCommandResult
import dev.studyflow.core.domain.sync.SyncOperation
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.testing.time.FakeDevice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The "same transaction" half of issue #55.
 *
 * A queue entry written *after* the mutation it describes can be lost to a crash in between, and
 * the change would then sit on the device forever, looking synced. These tests pin the guarantee
 * that prevents that: the entry is written by the same DAO transaction as the mutation, so either
 * both exist or neither does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [DATABASE_ROBOLECTRIC_SDK])
class SyncQueueTransactionTest {
    private lateinit var database: StudyFlowDatabase
    private lateinit var repository: OfflineFirstSessionRepository
    private lateinit var history: OfflineFirstSessionHistoryRepository
    private val device = FakeDevice()

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    StudyFlowDatabase::class.java,
                ).build()
        repository = OfflineFirstSessionRepository(database.sessionDao(), DEVICE_ID)
        history = OfflineFirstSessionHistoryRepository(database.sessionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `an active session is never queued`() =
        runBlocking {
            start()
            device.advance(5.minutes)
            repository.execute(TimerCommand.Pause, "event-pause", device.anchor())
            repository.execute(TimerCommand.Resume, "event-resume", device.anchor())

            // Three durable mutations, no queue entries: a running stopwatch belongs to the device
            // it runs on, and replicating every tick would cost a request for a number that is
            // stale on arrival.
            assertTrue(pending().isEmpty())
        }

    @Test
    fun `stopping queues the session together with the mutation`() =
        runBlocking {
            start()
            device.advance(25.minutes)
            val stopped = stop()

            val queued = pending().single()
            assertEquals(SESSION_ID, queued.entityId)
            assertEquals(SyncOperation.UPSERT, queued.operation)
            assertEquals(stopped.updatedAt, queued.updatedAt)
            assertEquals(DEVICE_ID, queued.deviceId)
        }

    @Test
    fun `a rejected command writes neither a session nor a queue entry`() =
        runBlocking {
            repository.execute(TimerCommand.Stop, "event-stop", device.anchor())

            assertEquals(0, database.sessionDao().count())
            assertTrue(pending().isEmpty())
        }

    @Test
    fun `repeated edits coalesce into one pending entry carrying the newest state`() =
        runBlocking {
            start()
            device.advance(25.minutes)
            stop()

            history.editNote(SESSION_ID, "first", "correction-1", NOW)
            history.editNote(SESSION_ID, "second", "correction-2", NOW + 1.minutes)

            // One entry, not three: the user sees "1 change waiting", and the drain reads the
            // current row, so the single entry still ships the newest note.
            val queued = pending().single()
            assertEquals(NOW + 1.minutes, queued.updatedAt)
            assertEquals(
                "second",
                database
                    .sessionDao()
                    .findWithEvents(SESSION_ID)
                    ?.session
                    ?.note,
            )
        }

    @Test
    fun `a correction is queued in the same transaction as its audit trail`() =
        runBlocking {
            start()
            device.advance(25.minutes)
            stop()
            database.syncDao().acknowledge(pending().map { it.sequence })

            history.editNote(SESSION_ID, "revised", "correction-1", NOW)

            assertEquals(1, database.sessionDao().lastCorrectionGroup(SESSION_ID).size)
            assertEquals(1, pending().size)
        }

    @Test
    fun `deleting a session queues a tombstone rather than removing the row`() =
        runBlocking {
            start()
            device.advance(25.minutes)
            stop()

            history.delete(SESSION_ID, "correction-1", NOW)

            val queued = pending().single()
            assertEquals(SyncOperation.DELETE, queued.operation)
            // The row survives as a tombstone: deleting it outright is what would let a stale
            // inbound copy recreate the session.
            assertTrue(
                database
                    .sessionDao()
                    .findWithEvents(SESSION_ID)
                    ?.session
                    ?.deleted == true,
            )
        }

    private suspend fun start() =
        repository
            .execute(TimerCommand.Start(SESSION_ID, subjectId = null, note = null), "event-start", device.anchor())
            .applied()

    private suspend fun stop() =
        repository
            .execute(TimerCommand.Stop, "event-stop", device.anchor())
            .applied()
            .session

    private suspend fun pending() = database.syncDao().pending(limit = 10).map { it.asExternalModel() }

    private fun SessionCommandResult.applied(): SessionCommandResult.Applied {
        assertTrue("expected an applied command but was $this", this is SessionCommandResult.Applied)
        return this as SessionCommandResult.Applied
    }

    private companion object {
        const val SESSION_ID = "session-1"
        const val DEVICE_ID = "device-under-test"
        val NOW: Instant = Instant.parse("2026-03-01T12:00:00Z")
    }
}
