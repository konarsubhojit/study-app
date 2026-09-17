package dev.studyflow.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Room
import dev.studyflow.core.database.DATABASE_ROBOLECTRIC_SDK
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.entity.FolderEntity
import dev.studyflow.core.database.entity.MaterialEntity
import dev.studyflow.core.database.entity.MaterialSyncState
import dev.studyflow.core.model.ContentHash
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class MaterialDaoTest {
    private lateinit var database: StudyFlowDatabase
    private lateinit var dao: MaterialDao

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StudyFlowDatabase::class.java)
                .build()
        dao = database.materialDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `paged browsing filters by folder type and tag`() =
        runBlocking {
            database.folderDao().upsert(FolderEntity("folder-a", null, "Folder A", BASE_TIME))
            dao.save(material("pdf", folderId = "folder-a", mimeType = "application/pdf"), setOf("exam"))
            dao.save(material("image", folderId = "folder-a", mimeType = "image/png"), setOf("exam"))
            dao.save(material("other-tag", folderId = "folder-a", mimeType = "application/pdf"), setOf("reading"))

            val ids =
                dao
                    .browsePaged(
                        folderId = "folder-a",
                        subjectId = null,
                        mimeTypePrefix = "application/pdf",
                        tag = "exam",
                    ).loadIds()

            assertEquals(listOf("pdf"), ids)
        }

    @Test
    fun `full text search matches names and notes while ignoring tombstones`() =
        runBlocking {
            dao.save(material("name-match", displayName = "Quantum notes.pdf"))
            dao.save(material("note-match", notes = "Worked examples for quantum mechanics"))
            dao.save(material("deleted", displayName = "Quantum deleted.pdf"))
            dao.softDelete("deleted", BASE_TIME + 10.minutes)

            val ids =
                dao
                    .searchPaged(
                        query = "quantum",
                        folderId = null,
                        subjectId = null,
                        mimeTypePrefix = null,
                        tag = null,
                        sort = MaterialSort.NAME_ASC,
                    ).loadIds()

            assertEquals(listOf("name-match", "note-match"), ids)
        }

    @Test
    fun `rename move tag delete and undo keep indexed views consistent`() =
        runBlocking {
            database.folderDao().upsert(FolderEntity("folder-a", null, "Folder A", BASE_TIME))
            database.folderDao().upsert(FolderEntity("folder-b", null, "Folder B", BASE_TIME))
            dao.save(
                material(
                    "material",
                    folderId = "folder-a",
                    displayName = "Draft.pdf",
                    syncState = MaterialSyncState.PENDING,
                ),
                setOf("draft"),
            )

            dao.rename("material", "Final.pdf", "exam solution", BASE_TIME + 1.minutes)
            dao.move("material", "folder-b", BASE_TIME + 2.minutes)
            dao.replaceTags("material", setOf("exam"))

            assertEquals(listOf("material"), dao.observeInFolder("folder-b").first().map(MaterialEntity::id))
            assertEquals(
                listOf("exam"),
                dao
                    .observeById("material")
                    .first()
                    ?.tags
                    ?.map { it.name },
            )
            assertEquals(listOf("material"), dao.searchPaged("solution", null, null, null, "exam").loadIds())
            assertEquals(listOf("material"), dao.uploadCandidates(limit = 10).map(MaterialEntity::id))

            dao.softDelete("material", BASE_TIME + 3.minutes)
            assertEquals(emptyList<String>(), dao.observeAll().first().map(MaterialEntity::id))
            assertEquals(emptyList<String>(), dao.searchPaged("solution", null, null, null, null).loadIds())
            assertEquals(emptyList<String>(), dao.uploadCandidates(limit = 10).map(MaterialEntity::id))

            dao.undoDelete("material", BASE_TIME + 4.minutes)
            assertEquals(listOf("material"), dao.searchPaged("solution", null, null, null, null).loadIds())
            assertEquals(listOf("material"), dao.uploadCandidates(limit = 10).map(MaterialEntity::id))
        }

    private suspend fun PagingSource<Int, MaterialEntity>.loadIds(): List<String> =
        when (
            val result =
                load(
                    PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
                )
        ) {
            is PagingSource.LoadResult.Page -> result.data.map(MaterialEntity::id)
            is PagingSource.LoadResult.Error -> throw result.throwable
            is PagingSource.LoadResult.Invalid -> error("PagingSource invalidated before loading")
        }

    private fun material(
        id: String,
        folderId: String? = null,
        displayName: String = "$id.pdf",
        mimeType: String = "application/pdf",
        notes: String? = null,
        syncState: MaterialSyncState = MaterialSyncState.SYNCED,
    ): MaterialEntity =
        MaterialEntity(
            id = id,
            folderId = folderId,
            subjectId = null,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = 1_024,
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
            notes = notes,
            remoteKey = "materials/$id",
            syncState = syncState,
            uploadedBytes = null,
            uploadTotalBytes = null,
            failureReason = null,
            failureRetryable = null,
            localPath = "/materials/$id",
            pinnedForOffline = false,
            encrypted = true,
            deleted = false,
        )

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-09-17T01:56:02.238Z")
    }
}
