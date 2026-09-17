package dev.studyflow.core.database.repository

import androidx.room.Room
import dev.studyflow.core.database.DATABASE_ROBOLECTRIC_SDK
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.entity.FolderEntity
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK
import dev.studyflow.core.testing.data.testContentHash
import dev.studyflow.core.testing.data.testMaterial
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [DATABASE_ROBOLECTRIC_SDK])
class OfflineFirstMaterialRepositoryTest {
    private lateinit var database: StudyFlowDatabase
    private lateinit var repository: OfflineFirstMaterialRepository

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StudyFlowDatabase::class.java)
                .build()
        repository = OfflineFirstMaterialRepository(database.materialDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a saved material round-trips and the catalogue lists newest first`() =
        runBlocking {
            repository.save(
                testMaterial(id = "older", displayName = "older.pdf", contentHash = testContentHash("older")),
            )
            repository.save(
                testMaterial(
                    id = "newer",
                    displayName = "newer.pdf",
                    contentHash = testContentHash("newer"),
                    createdAt = TEST_WALL_CLOCK + 1.hours,
                ),
            )

            val all = repository.observeAll().first()

            assertEquals(listOf("newer", "older"), all.map { it.id })
        }

    @Test
    fun `page count and duration round-trip through the database`() =
        runBlocking {
            val material =
                testMaterial(
                    id = "with-metadata",
                    contentHash = testContentHash("with-metadata"),
                    pageCount = 12,
                    duration = 90.minutes,
                )
            repository.save(material)

            val found = repository.findByContentHash(material.contentHash)

            assertEquals(12, found?.pageCount)
            assertEquals(90.minutes, found?.duration)
        }

    @Test
    fun `finding by content hash is how a duplicate import is detected`() =
        runBlocking {
            val hash = testContentHash("shared-bytes")
            val original = testMaterial(id = "original", contentHash = hash)
            repository.save(original)

            val found = repository.findByContentHash(hash)

            assertEquals(original, found)
        }

    @Test
    fun `observing by id reflects a saved material and null once it is gone`() =
        runBlocking {
            assertNull(repository.observeById("never-saved").first())

            val material = testMaterial(id = "detail-target", contentHash = testContentHash("detail-target"))
            repository.save(material)

            assertEquals(material, repository.observeById("detail-target").first())
        }

    @Test
    fun `no catalogue entry means no duplicate`() =
        runBlocking {
            val found = repository.findByContentHash(testContentHash("never-imported"))

            assertNull(found)
        }

    @Test
    fun `materials in a folder are isolated from the top level and other folders`() =
        runBlocking {
            database.folderDao().upsertAll(
                listOf(
                    FolderEntity(id = "folder-a", parentId = null, name = "Folder A", createdAt = TEST_WALL_CLOCK),
                    FolderEntity(id = "folder-b", parentId = null, name = "Folder B", createdAt = TEST_WALL_CLOCK),
                ),
            )
            repository.save(testMaterial(id = "top", folderId = null, contentHash = testContentHash("top")))
            repository.save(
                testMaterial(id = "in-a", folderId = "folder-a", contentHash = testContentHash("in-a")),
            )
            repository.save(
                testMaterial(id = "in-b", folderId = "folder-b", contentHash = testContentHash("in-b")),
            )

            assertEquals(listOf("top"), repository.observeInFolder(null).first().map { it.id })
            assertEquals(listOf("in-a"), repository.observeInFolder("folder-a").first().map { it.id })
        }
}
