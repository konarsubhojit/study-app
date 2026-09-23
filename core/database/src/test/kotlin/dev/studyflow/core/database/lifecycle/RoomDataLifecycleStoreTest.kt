package dev.studyflow.core.database.lifecycle

import androidx.room.Room
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.DeviceIdProvider
import dev.studyflow.core.database.DATABASE_ROBOLECTRIC_SDK
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.entity.asEntity
import dev.studyflow.core.domain.lifecycle.DataExporter
import dev.studyflow.core.domain.lifecycle.DataImporter
import dev.studyflow.core.domain.result.DomainResult
import dev.studyflow.core.model.SessionEventType
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.testing.data.FakeArchiveStorage
import dev.studyflow.core.testing.data.FakeMaterialFileStore
import dev.studyflow.core.testing.data.TEST_DEVICE_ID
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK
import dev.studyflow.core.testing.data.testMaterial
import dev.studyflow.core.testing.data.testSessionEvent
import dev.studyflow.core.testing.data.testStudySession
import dev.studyflow.core.testing.data.testStudyTask
import dev.studyflow.core.testing.data.testSubject
import dev.studyflow.core.testing.data.testTimeAnchor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
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

/**
 * The end-to-end promise of issue #78 against real SQLite: what one device exports, another
 * device's database ends up holding.
 *
 * The two databases stand in for "before the wipe" and "after the wipe"; everything between them
 * is the production code path, with only the Storage Access Framework and the material cache
 * replaced by in-memory fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [DATABASE_ROBOLECTRIC_SDK])
class RoomDataLifecycleStoreTest {
    private lateinit var exportingDatabase: StudyFlowDatabase
    private lateinit var importingDatabase: StudyFlowDatabase
    private val storage = FakeArchiveStorage()
    private val exportedFiles = FakeMaterialFileStore()
    private val restoredFiles = FakeMaterialFileStore()

    @Before
    fun setUp() {
        exportingDatabase = inMemoryDatabase()
        importingDatabase = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        exportingDatabase.close()
        importingDatabase.close()
    }

    @Test
    fun `an export restores subjects sessions tasks and materials onto an empty database`() =
        runBlocking {
            seedStudyData()

            export()
            val summary = import()

            assertEquals("every seeded record is restored", 4, summary.added)
            val restored = store(importingDatabase).readSnapshot()
            assertEquals("subject", listOf("subject-1"), restored.subjects.map { it.id })
            assertEquals("task", listOf("task-1"), restored.tasks.map { it.id })
            assertEquals("session", listOf("session-1"), restored.sessions.map { it.session.id })
            assertEquals("material", listOf("material-1"), restored.materials.map { it.id })
        }

    @Test
    fun `a restored session keeps its event log and its counted time`() =
        runBlocking {
            seedStudyData()

            export()
            import()

            val original = store(exportingDatabase).readSnapshot().sessions.single()
            val restored = store(importingDatabase).readSnapshot().sessions.single()
            assertEquals("event log", original.events, restored.events)
            assertEquals("counted time", original.session.elapsed.counted, restored.session.elapsed.counted)
            assertEquals("status", SessionStatus.STOPPED, restored.session.status)
        }

    @Test
    fun `the cached bytes of a material come back with it`() =
        runBlocking {
            seedStudyData()

            export()
            import()

            val localPath =
                store(importingDatabase)
                    .readSnapshot()
                    .materials
                    .single()
                    .localPath
            assertTrue("the restored row points at a restored file", localPath != null)
            assertEquals(
                "the bytes are the ones that were exported",
                MATERIAL_BYTES.decodeToString(),
                restoredFiles.files.getValue(localPath!!).decodeToString(),
            )
        }

    @Test
    fun `importing the same archive twice leaves one row per record`() =
        runBlocking {
            seedStudyData()
            export()

            import()
            val second = import()

            assertEquals("nothing is added the second time", 0, second.added)
            assertEquals("subjects", 1, importingDatabase.subjectDao().count())
            assertEquals("tasks", 1, importingDatabase.studyTaskDao().count())
            assertEquals("sessions", 1, importingDatabase.sessionDao().count())
            assertEquals("materials", 1, importingDatabase.materialDao().count())
        }

    @Test
    fun `a task edited after the export survives the import`() =
        runBlocking {
            seedStudyData()
            export()
            importingDatabase.studyTaskDao().saveAll(
                listOf(testStudyTask(title = "Edited here", updatedAt = TEST_WALL_CLOCK + 30.minutes).asEntity()),
            )

            import()

            assertEquals(
                "the newer local title wins",
                "Edited here",
                store(importingDatabase)
                    .readSnapshot()
                    .tasks
                    .single()
                    .title,
            )
        }

    @Test
    @Suppress("InjectDispatcher") // The point of the test is the real threading rule Room enforces.
    fun `erasing the database leaves no rows behind`() =
        runBlocking {
            seedStudyData()

            // Room refuses a blocking clear on the main thread; the coordinator always calls it
            // from its IO dispatcher, which is what this reproduces.
            withContext(Dispatchers.IO) { RoomDataEraser(exportingDatabase).erase() }

            assertEquals("subjects", 0, exportingDatabase.subjectDao().count())
            assertEquals("tasks", 0, exportingDatabase.studyTaskDao().count())
            assertEquals("sessions", 0, exportingDatabase.sessionDao().count())
            assertEquals("materials", 0, exportingDatabase.materialDao().count())
        }

    private suspend fun seedStudyData() {
        exportingDatabase.subjectDao().upsert(testSubject().asEntity())
        exportingDatabase.studyTaskDao().save(testStudyTask().asEntity())
        exportingDatabase.sessionDao().restore(
            sessions =
                listOf(
                    testStudySession(
                        status = SessionStatus.STOPPED,
                        endedAt = TEST_WALL_CLOCK + 25.minutes,
                    ).asEntity(),
                ),
            events =
                listOf(
                    testSessionEvent(id = "event-1", type = SessionEventType.STARTED, sequence = 0).asEntity(),
                    testSessionEvent(
                        id = "event-2",
                        type = SessionEventType.STOPPED,
                        anchor = testTimeAnchor(uptime = 25.minutes, wallClock = TEST_WALL_CLOCK + 25.minutes),
                        sequence = 1,
                    ).asEntity(),
                ),
        )
        exportingDatabase.materialDao().upsertAll(listOf(testMaterial(localUri = MATERIAL_PATH).asEntity()))
        exportedFiles.files[MATERIAL_PATH] = MATERIAL_BYTES
    }

    private suspend fun export() =
        DataExporter(
            localData = store(exportingDatabase),
            materialFiles = exportedFiles,
            sinkFactory = storage.sinkFactory,
            clock = Clock { TEST_WALL_CLOCK },
            dispatcherProvider = BlockingDispatchers,
        ).export(DESTINATION).valueOrFail()

    private suspend fun import() =
        DataImporter(
            sourceFactory = storage.sourceFactory,
            localData = store(importingDatabase),
            writer = store(importingDatabase),
            materialFiles = restoredFiles,
            dispatcherProvider = BlockingDispatchers,
        ).import(DESTINATION).valueOrFail()

    private fun store(database: StudyFlowDatabase) =
        RoomDataLifecycleStore(database, DeviceIdProvider { TEST_DEVICE_ID })

    private fun inMemoryDatabase(): StudyFlowDatabase =
        Room
            .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StudyFlowDatabase::class.java)
            .build()

    private fun <T> DomainResult<T>.valueOrFail(): T =
        when (this) {
            is DomainResult.Success -> value
            is DomainResult.Failure -> throw AssertionError("expected success but failed with $error")
        }

    /** Room's own dispatcher already moves the work off the caller; `runBlocking` supplies the rest. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private object BlockingDispatchers : DispatcherProvider {
        override val default: CoroutineDispatcher = UnconfinedTestDispatcher()
        override val io: CoroutineDispatcher = UnconfinedTestDispatcher()
        override val main: CoroutineDispatcher = UnconfinedTestDispatcher()
    }

    private companion object {
        const val DESTINATION = "content://downloads/studyflow-export.zip"
        const val MATERIAL_PATH = "/data/materials/material-1"
        val MATERIAL_BYTES = "%PDF-1.7 lecture notes".encodeToByteArray()
    }
}
