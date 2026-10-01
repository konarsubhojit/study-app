package dev.studyflow.core.scheduling

import dev.studyflow.core.domain.materials.MaterialUploadCoordinator
import dev.studyflow.core.domain.materials.UploadWaitReason
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.testing.data.FakeMaterialRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@DisplayName("MaterialStorageReconciler")
class MaterialStorageReconcilerTest {
    private val materialRepository = FakeMaterialRepository()
    private val objectStore = RecordingObjectStore()
    private val enqueued = mutableListOf<String>()
    private val uploadCoordinator =
        object : MaterialUploadCoordinator {
            override val waitReason: Flow<UploadWaitReason?> = flowOf(null)

            override suspend fun enqueueUpload(materialId: String) {
                enqueued += materialId
            }

            override fun cancelUpload(materialId: String) = Unit

            override suspend fun retryUpload(materialId: String) {
                enqueued += materialId
            }
        }
    private val reconciler = MaterialStorageReconciler(materialRepository, objectStore, uploadCoordinator)

    @Test
    fun `a synced material whose object never existed is queued for a real upload`() =
        runBlocking {
            // The damage a build that "uploaded" into memory left behind, on any device that ran
            // it: a Synced row, a key, and nothing behind the key.
            materialRepository.save(synced("m1"))

            reconciler.verifyStoredMaterials()

            val repaired = materialRepository.observeById("m1").first()
            assertEquals(SyncState.Pending, repaired?.sync)
            assertEquals(null, repaired?.remoteKey, "a key that resolves to nothing must not survive")
            assertEquals(listOf("m1"), enqueued)
        }

    @Test
    fun `a material the store really holds is left alone`() =
        runBlocking {
            materialRepository.save(synced("m1"))
            objectStore.seedStoredObject(ObjectKey("materials/$HASH"), SIZE, ContentHash(HASH))

            reconciler.verifyStoredMaterials()

            assertEquals(SyncState.Synced, materialRepository.observeById("m1").first()?.sync)
            assertTrue(enqueued.isEmpty(), "a stored material must not be re-uploaded")
        }

    @Test
    fun `a missing object with no local copy becomes an honest non-retryable failure`() =
        runBlocking {
            materialRepository.save(synced("m1").copy(localPath = null))

            reconciler.verifyStoredMaterials()

            val repaired = materialRepository.observeById("m1").first()
            val state = repaired?.sync
            assertTrue(state is SyncState.Failed && !state.retryable, "nothing can re-send bytes that are gone: $state")
            assertTrue(enqueued.isEmpty(), "an upload with no source would only fail again")
        }

    @Test
    fun `a material already verified is not checked twice`() =
        runBlocking {
            materialRepository.save(synced("m1"))
            objectStore.seedStoredObject(ObjectKey("materials/$HASH"), SIZE, ContentHash(HASH))

            reconciler.verifyStoredMaterials()
            reconciler.verifyStoredMaterials()

            assertEquals(1, objectStore.statCalls, "a healthy catalogue must not cost a request per sync")
        }

    private fun synced(id: String): Material =
        Material(
            id = id,
            displayName = "lecture.pdf",
            mimeType = "application/pdf",
            sizeBytes = SIZE,
            contentHash = ContentHash(HASH),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            localPath = "/tmp/$id.pdf",
            sync = SyncState.Synced,
            remoteKey = "materials/$HASH",
        )

    private companion object {
        const val SIZE = 1024L
        const val HASH = "b000000000000000000000000000000000000000000000000000000000000001"
    }
}
