package dev.studyflow.core.database.session

import androidx.paging.PagingSource
import androidx.room.Room
import dev.studyflow.core.database.DATABASE_ROBOLECTRIC_SDK
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.entity.SubjectEntity
import dev.studyflow.core.database.entity.asEntity
import dev.studyflow.core.domain.session.HistoryRejection
import dev.studyflow.core.domain.session.SessionHistoryCommandResult
import dev.studyflow.core.domain.session.SessionHistoryFilter
import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import kotlinx.coroutines.flow.first
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Persistence for session corrections (issue #32): that an edit, split, merge, manual entry or
 * delete lands in the same transaction as the audit row that explains it, and that undo restores
 * exactly what a correction changed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [DATABASE_ROBOLECTRIC_SDK])
class OfflineFirstSessionHistoryRepositoryTest {
    private lateinit var database: StudyFlowDatabase
    private lateinit var historyRepository: OfflineFirstSessionHistoryRepository

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    StudyFlowDatabase::class.java,
                ).build()
        historyRepository = OfflineFirstSessionHistoryRepository(database.sessionDao())
        runBlocking {
            database.subjectDao().upsertAll(
                listOf(
                    SubjectEntity(id = "math", name = "Math", colorArgb = 0, archived = false),
                    SubjectEntity(id = "history", name = "History", colorArgb = 0, archived = false),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `editing timing overrides the projection and records a correction`() =
        runBlocking {
            seedStoppedSession("session-1", start = EPOCH, end = EPOCH + 30.minutes)

            val result =
                historyRepository.editTiming(
                    sessionId = "session-1",
                    startedAt = EPOCH,
                    endedAt = EPOCH + 45.minutes,
                    correctionId = "correction-1",
                    at = EPOCH + 1.minutes,
                )

            val applied = result.applied()
            assertEquals(
                45.minutes,
                applied.sessions
                    .single()
                    .elapsed.counted,
            )
            assertTrue(applied.sessions.single().manualOverride)
            assertEquals(
                1,
                database
                    .sessionDao()
                    .observeCorrections("session-1")
                    .first()
                    .size,
            )
        }

    @Test
    fun `editing a running session's timing is rejected`() =
        runBlocking {
            seedSession(
                "session-1",
                subjectId = null,
                start = EPOCH,
                end = null,
                status = SessionStatus.RUNNING,
                elapsed = SessionElapsed(counted = Duration.ZERO),
            )

            val result =
                historyRepository.editTiming(
                    sessionId = "session-1",
                    startedAt = EPOCH,
                    endedAt = EPOCH + 5.minutes,
                    correctionId = "correction-1",
                    at = EPOCH,
                )

            assertEquals(SessionHistoryCommandResult.Rejected(HistoryRejection.SESSION_NOT_STOPPED), result)
        }

    @Test
    fun `splitting a session persists both halves and the correction`() =
        runBlocking {
            seedStoppedSession("session-1", start = EPOCH, end = EPOCH + 60.minutes)

            val result =
                historyRepository.split(
                    sessionId = "session-1",
                    at = EPOCH + 20.minutes,
                    newSessionId = "session-1-b",
                    correctionId = "correction-split",
                    now = EPOCH + 61.minutes,
                )

            val applied = result.applied()
            assertEquals(2, applied.sessions.size)
            val first = applied.sessions.single { it.id == "session-1" }
            val second = applied.sessions.single { it.id == "session-1-b" }
            assertEquals(20.minutes, first.elapsed.counted)
            assertEquals(40.minutes, second.elapsed.counted)
            assertEquals(2, database.sessionDao().count())
        }

    @Test
    fun `merging sessions tombstones the secondary rows`() =
        runBlocking {
            seedStoppedSession("session-1", start = EPOCH, end = EPOCH + 30.minutes, subjectId = "math")
            seedStoppedSession(
                "session-2",
                start = EPOCH + 60.minutes,
                end = EPOCH + 80.minutes,
                subjectId = "math",
            )

            val result =
                historyRepository.merge(
                    sessionIds = listOf("session-1", "session-2"),
                    correctionId = "correction-merge",
                    now = EPOCH + 90.minutes,
                )

            val applied = result.applied()
            val merged = applied.sessions.single { it.id == "session-1" }
            val secondary = applied.sessions.single { it.id == "session-2" }
            assertEquals(50.minutes, merged.elapsed.counted)
            assertTrue(secondary.deleted)
        }

    @Test
    fun `a manual entry is stopped and persisted without a timer log`() =
        runBlocking {
            val result =
                historyRepository.manualEntry(
                    deviceId = DEVICE_ID,
                    subjectId = "history",
                    note = "offline reading",
                    startedAt = EPOCH,
                    endedAt = EPOCH + 25.minutes,
                    sessionId = "manual-1",
                    correctionId = "correction-manual",
                    now = EPOCH + 25.minutes,
                )

            val applied = result.applied()
            assertEquals(
                25.minutes,
                applied.sessions
                    .single()
                    .elapsed.counted,
            )
            assertEquals(emptyList<Any>(), database.sessionDao().observeEvents("manual-1").first())
        }

    @Test
    fun `deleting then undoing restores the exact previous state`() =
        runBlocking {
            seedStoppedSession("session-1", start = EPOCH, end = EPOCH + 30.minutes, note = "before delete")

            historyRepository.delete("session-1", correctionId = "correction-delete", now = EPOCH + 31.minutes)
            val undone =
                historyRepository.undo("session-1", correctionId = "correction-undo", now = EPOCH + 32.minutes)

            val restored = undone.applied().sessions.single()
            assertEquals(false, restored.deleted)
            assertEquals("before delete", restored.note)
        }

    @Test
    fun `undoing a session with no corrections is rejected`() =
        runBlocking {
            seedStoppedSession("session-1", start = EPOCH, end = EPOCH + 30.minutes)

            val result = historyRepository.undo("session-1", correctionId = "correction-undo", now = EPOCH)

            assertEquals(SessionHistoryCommandResult.Rejected(HistoryRejection.NOTHING_TO_UNDO), result)
        }

    @Test
    fun `bulk delete tombstones every requested session under one correction each`() =
        runBlocking {
            seedStoppedSession("session-1", start = EPOCH, end = EPOCH + 10.minutes)
            seedStoppedSession("session-2", start = EPOCH, end = EPOCH + 10.minutes)

            val result =
                historyRepository.bulkDelete(
                    sessionIds = listOf("session-1", "session-2"),
                    correctionIdFor = { "correction-$it" },
                    now = EPOCH + 20.minutes,
                )

            val applied = result.applied()
            assertEquals(2, applied.sessions.size)
            assertTrue(applied.sessions.all(StudySession::deleted))
        }

    @Test
    fun `the history paging source excludes deleted sessions and honours the subject filter`() =
        runBlocking {
            seedStoppedSession("session-1", start = EPOCH, end = EPOCH + 10.minutes, subjectId = "math")
            seedStoppedSession("session-2", start = EPOCH, end = EPOCH + 10.minutes, subjectId = "history")
            historyRepository.delete("session-2", correctionId = "correction-delete", now = EPOCH + 20.minutes)
            seedStoppedSession("session-3", start = EPOCH, end = EPOCH + 10.minutes, subjectId = "math")

            val ids = historyRepository.historyPagingSource().loadIds()

            assertEquals(setOf("session-1", "session-3"), ids.toSet())
            assertEquals(
                listOf("session-3", "session-1"),
                historyRepository.historyPagingSource(SessionHistoryFilter(subjectId = "math")).loadIds(),
            )
        }

    @Test
    fun `daily totals sum counted time per subject per day from an already-loaded page`() =
        runBlocking {
            val first =
                seedStoppedSession("session-1", start = EPOCH, end = EPOCH + 30.minutes, subjectId = "math")
            val second =
                seedStoppedSession(
                    "session-2",
                    start = EPOCH + 40.minutes,
                    end = EPOCH + 60.minutes,
                    subjectId = "math",
                )

            val totals = historyRepository.dailyTotals(listOf(first, second))

            assertEquals(1, totals.size)
            assertEquals(50.minutes, totals.single().totalCounted)
            assertEquals("math", totals.single().subjectId)
        }

    /** Seeds a stopped session's row directly, bypassing corrections so the audit trail stays clean. */
    private suspend fun seedStoppedSession(
        id: String,
        start: Instant,
        end: Instant,
        subjectId: String? = null,
        note: String? = null,
    ): StudySession =
        seedSession(
            id = id,
            subjectId = subjectId,
            start = start,
            end = end,
            status = SessionStatus.STOPPED,
            elapsed = SessionElapsed(counted = end - start),
            note = note,
        )

    private suspend fun seedSession(
        id: String,
        subjectId: String?,
        start: Instant,
        end: Instant?,
        status: SessionStatus,
        elapsed: SessionElapsed,
        note: String? = null,
    ): StudySession {
        val session =
            StudySession(
                id = id,
                subjectId = subjectId,
                note = note,
                startedAt = start,
                endedAt = end,
                status = status,
                elapsed = elapsed,
                deviceId = DEVICE_ID,
                updatedAt = start,
                manualOverride = true,
            )
        database.sessionDao().upsertSessions(listOf(session.asEntity()))
        return session
    }

    private suspend fun PagingSource<Int, StudySession>.loadIds(): List<String> =
        when (
            val result =
                load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
        ) {
            is PagingSource.LoadResult.Page -> result.data.map(StudySession::id)
            is PagingSource.LoadResult.Error -> throw result.throwable
            is PagingSource.LoadResult.Invalid -> error("PagingSource invalidated before loading")
        }

    private fun SessionHistoryCommandResult.applied(): SessionHistoryCommandResult.Applied {
        assertTrue("expected an applied correction but was $this", this is SessionHistoryCommandResult.Applied)
        return this as SessionHistoryCommandResult.Applied
    }

    private companion object {
        val EPOCH: Instant = Instant.parse("2026-03-01T09:00:00Z")
        const val DEVICE_ID = "device-1"
    }
}
