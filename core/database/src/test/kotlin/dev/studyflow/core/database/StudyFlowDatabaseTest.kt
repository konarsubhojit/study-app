package dev.studyflow.core.database

import androidx.room.Room
import dev.studyflow.core.database.entity.SessionEventEntity
import dev.studyflow.core.database.entity.StudySessionEntity
import dev.studyflow.core.database.entity.SubjectEntity
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.SessionEventType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [DATABASE_ROBOLECTRIC_SDK])
class StudyFlowDatabaseTest {
    private lateinit var database: StudyFlowDatabase

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    StudyFlowDatabase::class.java,
                ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `Flow list query emits stable subject ordering after writes`() =
        runBlocking {
            database.subjectDao().upsert(SubjectEntity("b", "Biology", 2, archived = false))
            database.subjectDao().upsert(SubjectEntity("a", "Algebra", 1, archived = false))
            database.subjectDao().upsert(SubjectEntity("z", "Archived", 3, archived = true))

            assertEquals(
                listOf("a", "b", "z"),
                database
                    .subjectDao()
                    .observeAll()
                    .first()
                    .map { it.id },
            )
        }

    @Test
    fun `session creation rolls metadata back when event insert fails`() =
        runBlocking {
            val firstSession = StudySessionEntity("session-a", null, null)
            val firstEvent = event(id = "duplicate-event", sessionId = firstSession.id)
            database.sessionDao().create(firstSession, firstEvent)

            val result =
                runCatching {
                    database
                        .sessionDao()
                        .create(
                            StudySessionEntity("session-b", null, null),
                            event(id = "duplicate-event", sessionId = "session-b"),
                        )
                }

            assertTrue(result.isFailure)
            assertEquals(
                listOf("session-a"),
                database
                    .sessionDao()
                    .observeAll()
                    .first()
                    .map { it.session.id },
            )
        }

    @Test
    fun `synthetic seed is atomic and idempotent`() =
        runBlocking {
            val size = SyntheticDataSize(3, 6, 24, 10, 4, 4)
            val data = SyntheticDataFactory.create(size)

            assertTrue(SyntheticDataSeeder.seedIfEmpty(database, data))
            assertFalse(SyntheticDataSeeder.seedIfEmpty(database, data))
            assertEquals(size.subjectCount, database.subjectDao().count())
            assertEquals(size.folderCount, database.folderDao().count())
            assertEquals(size.materialCount, database.materialDao().count())
            // Row count, not the list flow: the flow deliberately hides the tombstoned rows the
            // fixture contains so that soft deletion is exercised by the list queries too.
            assertEquals(size.taskCount, database.studyTaskDao().count())
            assertEquals(
                size.sessionCount,
                database
                    .sessionDao()
                    .observeAll()
                    .first()
                    .size,
            )
        }

    private fun event(
        id: String,
        sessionId: String,
    ): SessionEventEntity =
        SessionEventEntity(
            id = id,
            sessionId = sessionId,
            type = SessionEventType.STARTED,
            uptime = 10.minutes,
            wallClock = Instant.parse("2026-09-16T23:24:29.317Z"),
            bootId = BootId("boot-a"),
            sequence = 0,
        )
}
