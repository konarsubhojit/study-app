package dev.studyflow.core.database.session

import androidx.room.Room
import dev.studyflow.core.database.DATABASE_ROBOLECTRIC_SDK
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.entity.asEntity
import dev.studyflow.core.domain.result.DomainError
import dev.studyflow.core.domain.session.SessionCommandObserver
import dev.studyflow.core.domain.session.SessionCommandResult
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.domain.timer.TimerRejection
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.SessionEventType
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.testing.time.FakeDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The persistence half of the timer's durability claim.
 *
 * `TimerEngineTest` proves the arithmetic survives reboots and clock changes; these tests prove the
 * *storage* survives being interrupted — that a command is either fully recorded or not recorded at
 * all, and that whatever a crash leaves behind still replays to the right answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [DATABASE_ROBOLECTRIC_SDK])
class OfflineFirstSessionRepositoryTest {
    private lateinit var database: StudyFlowDatabase
    private lateinit var repository: OfflineFirstSessionRepository
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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `starting a session writes the event and the projection together`() =
        runBlocking {
            val applied = repository.start()

            assertEquals(SessionStatus.RUNNING, applied.session.status)
            assertEquals(DEVICE_ID, applied.session.deviceId)
            assertEquals(
                listOf(SessionEventType.STARTED),
                database
                    .sessionDao()
                    .observeEvents(SESSION_ID)
                    .first()
                    .map { it.type },
            )
            assertEquals(SESSION_ID, repository.observeActiveSession().first()?.id)
        }

    @Test
    fun `at most one session is active on a device`() =
        runBlocking {
            repository.start()

            val second = repository.execute(TimerCommand.Start("session-2"), "event-second", device.anchor())

            assertEquals(SessionCommandResult.Rejected(TimerRejection.SESSION_ALREADY_ACTIVE), second)
            assertEquals(listOf(SESSION_ID), database.sessionDao().activeSessionIds(DEVICE_ID))
        }

    @Test
    fun `a session on another device does not block this one`() =
        runBlocking {
            val other = OfflineFirstSessionRepository(database.sessionDao(), "other-device")
            other.execute(TimerCommand.Start("session-elsewhere"), "event-elsewhere", device.anchor())

            val applied = repository.start()

            assertEquals(SESSION_ID, applied.session.id)
            assertEquals(SESSION_ID, repository.observeActiveSession().first()?.id)
            assertEquals("session-elsewhere", other.observeActiveSession().first()?.id)
        }

    @Test
    fun `an illegal transition is rejected without writing anything`() =
        runBlocking {
            repository.start()
            repository.execute(TimerCommand.Pause, "event-pause", device.anchor())

            val again = repository.execute(TimerCommand.Pause, "event-pause-again", device.anchor())

            assertEquals(SessionCommandResult.Rejected(TimerRejection.NOT_RUNNING), again)
            assertEquals(
                2,
                database
                    .sessionDao()
                    .observeEvents(SESSION_ID)
                    .first()
                    .size,
            )
        }

    @Test
    fun `commands against no session are rejected`() =
        runBlocking {
            val result = repository.execute(TimerCommand.Resume, "event-resume", device.anchor())

            assertEquals(SessionCommandResult.Rejected(TimerRejection.NO_ACTIVE_SESSION), result)
            assertEquals(0, database.sessionDao().count())
        }

    @Test
    fun `stopping closes the session and empties the active slot`() =
        runBlocking {
            repository.start()
            device.advance(30.minutes)

            val stopped = repository.execute(TimerCommand.Stop, "event-stop", device.anchor()).applied()

            assertEquals(SessionStatus.STOPPED, stopped.session.status)
            assertEquals(30.minutes, stopped.session.elapsed.counted)
            assertNotNull(stopped.session.endedAt)
            assertNull(repository.observeActiveSession().first())
            assertEquals(SESSION_ID, repository.observeSession(SESSION_ID).first()?.id)
        }

    @Test
    fun `a projection lost to a crash is rebuilt from the log`() =
        runBlocking {
            val started = repository.start()
            device.advance(12.minutes)

            // Exactly what a kill between the two writes would leave: the event is durable, the
            // projection still describes the previous state.
            database.sessionDao().appendEvent(pauseEvent(sequence = 1).asEntity())

            val session = requireNotNull(repository.observeSession(SESSION_ID).first())
            assertEquals(SessionStatus.PAUSED, session.status)
            assertEquals(12.minutes, session.elapsed.counted)
            assertEquals(SessionStatus.RUNNING, started.session.status)
            // The stale projection row still reads as active, so recovery finds the session.
            assertEquals(SESSION_ID, repository.observeActiveSession().first()?.id)
            assertTrue(repository.activeState() is TimerState.Paused)

            // And the next command continues from the log, not from the stale row.
            val resumed = repository.execute(TimerCommand.Resume, "event-resume", device.anchor()).applied()
            assertEquals(SessionStatus.RUNNING, resumed.session.status)
            assertEquals(12.minutes, resumed.session.elapsed.counted)
        }

    @Test
    fun `retrying a partially written event repairs the projection without duplicating the log`() =
        runBlocking {
            repository.start()
            device.advance(12.minutes)
            val partial = pauseEvent(sequence = 1)
            database.sessionDao().appendEvent(partial.asEntity())

            val retried = repository.execute(TimerCommand.Pause, partial.id, partial.anchor).applied()

            assertEquals(SessionStatus.PAUSED, retried.session.status)
            assertEquals(12.minutes, retried.session.elapsed.counted)
            assertEquals(
                listOf(SessionEventType.STARTED, SessionEventType.PAUSED),
                database
                    .sessionDao()
                    .observeEvents(SESSION_ID)
                    .first()
                    .map { it.type },
            )
            assertEquals(SessionStatus.PAUSED, repository.observeActiveSession().first()?.status)
        }

    @Test
    fun `focus break transitions are persisted as explicit events`() =
        runBlocking {
            repository.start()
            device.advance(25.minutes)
            val breakStarted = repository.execute(TimerCommand.StartBreak, "event-break", device.anchor()).applied()
            device.advance(5.minutes)
            val focusResumed = repository.execute(TimerCommand.ResumeFocus, "event-focus", device.anchor()).applied()

            assertEquals(SessionStatus.PAUSED, breakStarted.session.status)
            assertEquals(25.minutes, breakStarted.session.elapsed.counted)
            assertEquals(SessionStatus.RUNNING, focusResumed.session.status)
            assertEquals(25.minutes, focusResumed.session.elapsed.counted)
            assertEquals(
                listOf(SessionEventType.STARTED, SessionEventType.BREAK_STARTED, SessionEventType.FOCUS_RESUMED),
                database
                    .sessionDao()
                    .observeEvents(SESSION_ID)
                    .first()
                    .map { it.type },
            )
        }

    @Test
    fun `retrying an already committed event returns the original outcome`() =
        runBlocking {
            repository.start()
            device.advance(12.minutes)
            val first = repository.execute(TimerCommand.Pause, "event-pause", device.anchor()).applied()

            val retried = repository.execute(TimerCommand.Pause, "event-pause", device.anchor()).applied()

            assertEquals(first.session, retried.session)
            assertEquals(
                listOf(SessionEventType.STARTED, SessionEventType.PAUSED),
                database
                    .sessionDao()
                    .observeEvents(SESSION_ID)
                    .first()
                    .map { it.type },
            )
        }

    @Test
    fun `reusing an event id for a different command is rejected as invalid`() =
        runBlocking {
            repository.start()
            device.advance(12.minutes)
            repository.execute(TimerCommand.Pause, "event-pause", device.anchor()).applied()

            val result = repository.execute(TimerCommand.Stop, "event-pause", device.anchor())

            assertEquals(SessionCommandResult.Failed(DomainError.Validation), result)
        }

    @Test
    fun `a failed append leaves neither the event nor the projection`() =
        runBlocking {
            repository.start()
            val stopped =
                requireNotNull(repository.observeSession(SESSION_ID).first())
                    .copy(status = SessionStatus.STOPPED, endedAt = device.now())
            // Same id as the start event, so the insert fails after the projection was written.
            val duplicate = pauseEvent(sequence = 1).copy(id = "event-start")

            val result =
                runCatching { database.sessionDao().appendAndProject(stopped.asEntity(), duplicate.asEntity()) }

            assertTrue(result.isFailure)
            assertEquals(
                listOf(SessionEventType.STARTED),
                database
                    .sessionDao()
                    .observeEvents(SESSION_ID)
                    .first()
                    .map { it.type },
            )
            assertEquals(SessionStatus.RUNNING, repository.observeActiveSession().first()?.status)
        }

    @Test
    fun `a reboot while running is reconciled into unverified time`() =
        runBlocking {
            repository.start()
            device.advance(20.minutes)
            device.reboot(downtime = 40.minutes)

            val reconciled = repository.reconcile("event-reboot", device.anchor()).applied()

            assertEquals(SessionStatus.PAUSED, reconciled.session.status)
            assertEquals(Duration.ZERO, reconciled.session.elapsed.counted)
            assertEquals(60.minutes, reconciled.session.elapsed.unverified)
        }

    @Test
    fun `a same boot session exceeding the maximum is reconciled to paused`() =
        runBlocking {
            val cappedRepository =
                OfflineFirstSessionRepository(
                    database.sessionDao(),
                    DEVICE_ID,
                    maximumRunningDuration = 2.hours,
                )
            cappedRepository.start()
            device.advance(3.hours)

            val reconciled = cappedRepository.reconcile("event-cap", device.anchor()).applied()

            assertEquals(SessionStatus.PAUSED, reconciled.session.status)
            assertEquals(2.hours, reconciled.session.elapsed.counted)
            assertEquals(Duration.ZERO, reconciled.session.elapsed.unverified)
        }

    @Test
    fun `reconciliation without a session writes nothing`() =
        runBlocking {
            val result = repository.reconcile("event-reboot", device.anchor())

            assertEquals(SessionCommandResult.Unchanged(TimerState.Idle), result)
            assertEquals(0, database.sessionDao().count())
        }

    @Test
    fun `applied commands notify observers after storage commits`() =
        runBlocking {
            val observed = mutableListOf<SessionCommandResult.Applied>()
            repository =
                OfflineFirstSessionRepository(
                    database.sessionDao(),
                    DEVICE_ID,
                    observers = setOf(SessionCommandObserver { observed += it }),
                )

            val applied = repository.start()

            assertEquals(listOf(applied), observed)
            assertEquals(
                SESSION_ID,
                database
                    .sessionDao()
                    .observeSession(SESSION_ID)
                    .first()
                    ?.session
                    ?.id,
            )
        }

    @Test
    fun `rejected commands do not notify command observers`() =
        runBlocking {
            var notifications = 0
            repository =
                OfflineFirstSessionRepository(
                    database.sessionDao(),
                    DEVICE_ID,
                    observers = setOf(SessionCommandObserver { notifications++ }),
                )

            repository.execute(TimerCommand.Pause, "event-pause", device.anchor())

            assertEquals(0, notifications)
        }

    private suspend fun OfflineFirstSessionRepository.start(): SessionCommandResult.Applied =
        execute(TimerCommand.Start(SESSION_ID, subjectId = null, note = "Algebra"), "event-start", device.anchor())
            .applied()

    private fun pauseEvent(sequence: Long): SessionEvent =
        SessionEvent(
            id = "event-$sequence",
            sessionId = SESSION_ID,
            type = SessionEventType.PAUSED,
            anchor = device.anchor(),
            sequence = sequence,
        )

    private fun SessionCommandResult.applied(): SessionCommandResult.Applied {
        assertTrue("expected an applied command but was $this", this is SessionCommandResult.Applied)
        return this as SessionCommandResult.Applied
    }

    private companion object {
        const val SESSION_ID = "session-1"
        const val DEVICE_ID = "device-1"
    }
}
