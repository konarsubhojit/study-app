package dev.studyflow.core.database.dao

import androidx.room.Room
import dev.studyflow.core.database.DATABASE_ROBOLECTRIC_SDK
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.entity.MaterialEntity
import dev.studyflow.core.database.entity.MaterialSyncState
import dev.studyflow.core.database.entity.MaterialUploadPartEntity
import dev.studyflow.core.model.ContentHash
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
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [DATABASE_ROBOLECTRIC_SDK])
class MaterialUploadPartDaoTest {
    private lateinit var database: StudyFlowDatabase
    private lateinit var materialDao: MaterialDao
    private lateinit var dao: MaterialUploadPartDao

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StudyFlowDatabase::class.java)
                .build()
        materialDao = database.materialDao()
        dao = database.materialUploadPartDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `completed parts come back ordered by part number regardless of insertion order`() =
        runBlocking {
            materialDao.save(material("lecture"))
            dao.upsert(part("lecture", number = 3))
            dao.upsert(part("lecture", number = 1))
            dao.upsert(part("lecture", number = 2))

            val numbers = dao.completedParts("lecture").map { it.partNumber }

            assertEquals(listOf(1, 2, 3), numbers)
        }

    @Test
    fun `re-acknowledging a part number replaces its etag rather than duplicating the row`() =
        runBlocking {
            materialDao.save(material("lecture"))
            dao.upsert(part("lecture", number = 1, etag = "first-attempt"))
            dao.upsert(part("lecture", number = 1, etag = "second-attempt"))

            val parts = dao.completedParts("lecture")

            assertEquals(1, parts.size)
            assertEquals("second-attempt", parts.single().etag)
        }

    @Test
    fun `clearing a material drops every one of its part rows`() =
        runBlocking {
            materialDao.save(material("lecture"))
            dao.upsert(part("lecture", number = 1))
            dao.upsert(part("lecture", number = 2))

            dao.clear("lecture")

            assertTrue(dao.completedParts("lecture").isEmpty())
        }

    @Test
    fun `deleting the material cascades to its part progress`() =
        runBlocking {
            materialDao.save(material("lecture"))
            dao.upsert(part("lecture", number = 1))

            database.openHelper.writableDatabase.execSQL("DELETE FROM materials WHERE id = 'lecture'")

            assertTrue(dao.completedParts("lecture").isEmpty())
        }

    private fun part(
        materialId: String,
        number: Int,
        etag: String = "etag-$number",
    ): MaterialUploadPartEntity =
        MaterialUploadPartEntity(
            materialId = materialId,
            partNumber = number,
            etag = etag,
            sizeBytes = 8 * 1024 * 1024L,
            completedAt = BASE_TIME,
        )

    private fun material(id: String): MaterialEntity =
        MaterialEntity(
            id = id,
            folderId = null,
            subjectId = null,
            displayName = "$id.mp4",
            mimeType = "video/mp4",
            sizeBytes = 500L * 1024 * 1024,
            contentHash =
                ContentHash(
                    id
                        .hashCode()
                        .toUInt()
                        .toString(16)
                        .padStart(64, '0')
                        .takeLast(64),
                ),
            createdAt = BASE_TIME,
            updatedAt = BASE_TIME,
            notes = null,
            remoteKey = null,
            syncState = MaterialSyncState.UPLOADING,
            uploadedBytes = 0,
            uploadTotalBytes = 500L * 1024 * 1024,
            failureReason = null,
            failureRetryable = null,
            localPath = "file:///lecture.mp4",
            pinnedForOffline = false,
            encrypted = false,
            deleted = false,
            pageCount = null,
            duration = null,
        )

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-09-01T00:00:00Z")
    }
}
