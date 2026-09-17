package dev.studyflow.core.scheduling

import dev.studyflow.core.domain.materials.CompletedUploadPart
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStoreException
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.data.FakeUploadProgressStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@DisplayName("MaterialUploadEngine")
class MaterialUploadEngineTest {
    private val createdAt = Instant.parse("2026-01-01T00:00:00Z")
    private val materialRepository = FakeMaterialRepository()
    private val uploadProgressStore = FakeUploadProgressStore()
    private val objectStore = RecordingObjectStore()

    // 20 MiB, which UploadPlanner.S3_COMPATIBLE splits into three parts (8 MiB, 8 MiB, 4 MiB) —
    // enough to prove a resume skips exactly the parts already acknowledged, not just "some" of them.
    private val totalBytes = 20L * 1024 * 1024
    private val contentHash = ContentHash("b".repeat(64))

    private val engine =
        MaterialUploadEngine(
            materialRepository = materialRepository,
            uploadProgressStore = uploadProgressStore,
            objectStore = objectStore,
            readPart = { _, part -> ByteArray(part.size.toInt()) },
        )

    @Test
    fun `a resumed upload never re-sends a part the server already acknowledged`() =
        runBlocking {
            materialRepository.save(material())
            val key = ObjectKey.ofMaterial(contentHash)
            objectStore.seedAcknowledgedPart(key, number = 1, size = 8 * 1024 * 1024)
            uploadProgressStore.recordCompletedPart(
                "m1",
                CompletedUploadPart(
                    number = 1,
                    etag = "etag-1",
                    sizeBytes =
                        8 * 1024 * 1024,
                ),
            )

            val outcome = engine.upload("m1")

            assertEquals(UploadOutcome.Synced, outcome)
            assertFalse(1 in objectStore.uploadedPartNumbers, "part 1 was already acknowledged and must not be re-sent")
            assertEquals(listOf(2, 3), objectStore.uploadedPartNumbers)
            assertEquals(SyncState.Synced, materialRepository.observeById("m1").first()?.sync)
        }

    @Test
    fun `a corrupted upload never reaches Synced`() =
        runBlocking {
            materialRepository.save(material())
            objectStore.failCompleteUpload = ObjectStoreException.Integrity("checksum mismatch")

            val outcome = engine.upload("m1")

            assertInstanceOf(UploadOutcome.Permanent::class.java, outcome)
            val sync = materialRepository.observeById("m1").first()?.sync
            assertInstanceOf(SyncState.Failed::class.java, sync)
            assertFalse((sync as SyncState.Failed).retryable, "an integrity failure is never worth retrying as-is")
        }

    @Test
    fun `a transient part failure is reported as retryable and leaves the material resumable`() =
        runBlocking {
            materialRepository.save(material())
            objectStore.failNextUploadPart = ObjectStoreException.Transient("connection reset")

            val outcome = engine.upload("m1")

            assertInstanceOf(UploadOutcome.Retryable::class.java, outcome)
            val sync = materialRepository.observeById("m1").first()?.sync
            assertTrue(sync is SyncState.Failed && sync.retryable, "a transient failure must stay retryable")
        }

    @Test
    fun `a full upload from scratch completes every part exactly once and marks the material synced`() =
        runBlocking {
            materialRepository.save(material())

            val outcome = engine.upload("m1")

            assertEquals(UploadOutcome.Synced, outcome)
            assertEquals(listOf(1, 2, 3), objectStore.uploadedPartNumbers)
            val updated = materialRepository.observeById("m1").first()
            assertEquals(SyncState.Synced, updated?.sync)
            assertEquals("materials/${contentHash.hex}", updated?.remoteKey)
        }

    @Test
    fun `an already-synced material is a no-op that never re-uploads`() =
        runBlocking {
            materialRepository.save(
                material().copy(sync = SyncState.Synced, remoteKey = "materials/${contentHash.hex}"),
            )

            val outcome = engine.upload("m1")

            assertEquals(UploadOutcome.Synced, outcome)
            assertTrue(objectStore.uploadedPartNumbers.isEmpty(), "an already-synced material must not be re-uploaded")
        }

    private fun material(): Material =
        Material(
            id = "m1",
            displayName = "lecture.mp4",
            mimeType = "video/mp4",
            sizeBytes = totalBytes,
            contentHash = contentHash,
            createdAt = createdAt,
            localPath = "/tmp/lecture.mp4",
        )
}
