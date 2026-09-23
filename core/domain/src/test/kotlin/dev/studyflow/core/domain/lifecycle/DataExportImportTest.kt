package dev.studyflow.core.domain.lifecycle

import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.domain.result.DomainError
import dev.studyflow.core.domain.result.DomainResult
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.testing.coroutines.testDispatcherProvider
import dev.studyflow.core.testing.data.FakeArchiveStorage
import dev.studyflow.core.testing.data.FakeLocalDataStore
import dev.studyflow.core.testing.data.FakeMaterialFileStore
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK
import dev.studyflow.core.testing.data.testContentHash
import dev.studyflow.core.testing.data.testMaterial
import dev.studyflow.core.testing.data.testSessionEvent
import dev.studyflow.core.testing.data.testStudySession
import dev.studyflow.core.testing.data.testStudyTask
import dev.studyflow.core.testing.data.testSubject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Export and import")
class DataExportImportTest {
    private val storage = FakeArchiveStorage()
    private val source = FakeLocalDataStore()
    private val sourceFiles = FakeMaterialFileStore()
    private val destination = FakeLocalDataStore()
    private val destinationFiles = FakeMaterialFileStore()

    @Test
    fun `an export written on one device restores the same data on another`() =
        runTest {
            givenAStudentsData()

            val summary = exporter().export(DESTINATION).valueOrFail()
            val restored = importer().import(DESTINATION).valueOrFail()

            assertEquals(4, summary.records)
            assertEquals(1, summary.materialFiles)
            assertEquals(4, restored.added)
            assertEquals(1, restored.restoredFiles)
            assertEquals(source.subjects, destination.subjects)
            assertEquals(source.tasks, destination.tasks)
            assertEquals(
                source.sessions.single().events,
                destination.sessions.single().events,
                "a restored session keeps the event log it is derived from",
            )
            assertTrue(
                destinationFiles.files.values
                    .single()
                    .contentEquals(FILE_BYTES),
                "the original cached bytes are restored, not just the catalogue row",
            )
        }

    @Test
    fun `importing the same archive twice adds nothing the second time`() =
        runTest {
            givenAStudentsData()
            exporter().export(DESTINATION).valueOrFail()

            val first = importer().import(DESTINATION).valueOrFail()
            val second = importer().import(DESTINATION).valueOrFail()

            assertEquals(4, first.added)
            assertEquals(0, second.added)
            assertEquals(0, second.updated)
            assertEquals(4, second.unchanged)
            assertEquals(1, destination.materials.size)
            assertEquals(1, destination.sessions.size)
            assertEquals(1, destination.tasks.size)
        }

    @Test
    fun `work done on the importing device after the export is not clobbered`() =
        runTest {
            givenAStudentsData()
            exporter().export(DESTINATION).valueOrFail()
            destination.tasks +=
                testStudyTask(id = "task-1", title = "Renamed here", updatedAt = TEST_WALL_CLOCK + 2.hours)

            val summary = importer().import(DESTINATION).valueOrFail()

            assertEquals("Renamed here", destination.tasks.single().title)
            assertTrue(summary.unchanged >= 1)
        }

    @Test
    fun `a session that was running at export time never comes back as a live timer`() =
        runTest {
            source.sessions +=
                SessionWithLog(
                    session = testStudySession(status = SessionStatus.RUNNING),
                    events = listOf(testSessionEvent(sequence = 0)),
                )
            exporter().export(DESTINATION).valueOrFail()

            importer().import(DESTINATION).valueOrFail()

            assertEquals(
                SessionStatus.STOPPED,
                destination.sessions
                    .single()
                    .session.status,
            )
        }

    @Test
    fun `an entry that tries to escape the extraction root is refused and nothing else is lost`() =
        runTest {
            givenAStudentsData()
            exporter().export(DESTINATION).valueOrFail()
            storage.corrupt(DESTINATION, "../../databases/studyflow.db", byteArrayOf(1, 2, 3))

            val summary = importer().import(DESTINATION).valueOrFail()

            assertEquals(1, summary.rejectedEntries)
            assertEquals(4, summary.added)
            assertTrue(
                destinationFiles.files.keys.none { it.contains("..") },
                "an archive entry name must never become a path",
            )
        }

    @Test
    fun `a material whose bytes were tampered out of the archive still restores its catalogue row`() =
        runTest {
            givenAStudentsData()
            exporter().export(DESTINATION).valueOrFail()
            storage.corrupt(DESTINATION, MATERIAL_ENTRY, content = null)

            importer().import(DESTINATION).valueOrFail()

            val restored = destination.materials.single()
            assertEquals("lecture-notes.pdf", restored.displayName)
            assertTrue(restored.localPath == null, "a row with no bytes must not claim a local copy")
        }

    @Test
    fun `a file that is not an archive at all fails as a validation error`() =
        runTest {
            storage.corrupt(DESTINATION, "holiday-photo.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte()))

            val result = importer().import(DESTINATION)

            assertEquals(DomainError.Validation, (result as DomainResult.Failure).error)
            assertTrue(destination.applied.isEmpty(), "a refused archive must write nothing")
        }

    @Test
    fun `an export to a destination the user revoked fails instead of half-writing`() =
        runTest {
            givenAStudentsData()
            storage.failToOpen = true

            val result = exporter().export(DESTINATION)

            assertEquals(DomainError.Network, (result as DomainResult.Failure).error)
            assertFalse(storage.archives.containsKey(DESTINATION))
        }

    @Test
    fun `a material with no cached copy is exported as metadata and counted`() =
        runTest {
            source.materials += testMaterial(id = "material-cloud-only", contentHash = testContentHash("cloud"))

            val summary = exporter().export(DESTINATION).valueOrFail()

            assertEquals(0, summary.materialFiles)
            assertEquals(1, summary.materialsWithoutBytes)
            assertNotNull(storage.archives.getValue(DESTINATION)[ARCHIVE_DOCUMENT_ENTRY])
        }

    private fun givenAStudentsData() {
        source.subjects += testSubject()
        source.sessions +=
            SessionWithLog(
                session = testStudySession(status = SessionStatus.STOPPED, endedAt = TEST_WALL_CLOCK + 1.hours),
                events = listOf(testSessionEvent(id = "event-1", sequence = 0)),
            )
        source.tasks += testStudyTask()
        source.materials += testMaterial(localUri = LOCAL_PATH)
        sourceFiles.files[LOCAL_PATH] = FILE_BYTES
    }

    private fun TestScope.exporter() =
        DataExporter(
            localData = source,
            materialFiles = sourceFiles,
            sinkFactory = storage.sinkFactory,
            clock = Clock { TEST_WALL_CLOCK },
            dispatcherProvider = testDispatcherProvider(),
        )

    private fun TestScope.importer() =
        DataImporter(
            sourceFactory = storage.sourceFactory,
            localData = destination,
            writer = destination,
            materialFiles = destinationFiles,
            dispatcherProvider = testDispatcherProvider(),
        )

    private fun <T> DomainResult<T>.valueOrFail(): T =
        when (this) {
            is DomainResult.Success -> value
            is DomainResult.Failure -> error("expected success but failed with $error")
        }

    private companion object {
        const val DESTINATION = "content://downloads/studyflow-export.zip"
        const val LOCAL_PATH = "/data/materials/material-1"
        const val MATERIAL_ENTRY = "materials/material-1-lecture-notes.pdf"
        val FILE_BYTES = "%PDF-1.7 lecture notes".encodeToByteArray()
    }
}
