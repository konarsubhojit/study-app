package dev.studyflow.core.scheduling

import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.domain.materials.CompletedUploadPart
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStoreException
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.data.FakeUploadProgressStore
import dev.studyflow.core.testing.logging.RecordingAppLogger
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
    fun `upload diagnostics identify every transfer stage and the failing part`() =
        runBlocking {
            val logger = RecordingAppLogger()
            val materialId = "00000000-0000-0000-0000-000000000001"
            materialRepository.save(material().copy(id = materialId))
            objectStore.failNextUploadPart = ObjectStoreException.Transient("connection reset")
            val diagnosticEngine =
                MaterialUploadEngine(
                    materialRepository = materialRepository,
                    uploadProgressStore = uploadProgressStore,
                    objectStore = objectStore,
                    readPart = { _, part -> ByteArray(part.size.toInt()) },
                    logger = logger,
                )

            assertInstanceOf(UploadOutcome.Retryable::class.java, diagnosticEngine.upload(materialId))
            assertEquals(UploadOutcome.Synced, diagnosticEngine.upload(materialId))

            val events = logger.diagnosticsWith(DiagnosticCode.MaterialUpload)
            assertTrue(events.any { "stage=PLAN outcome=SUCCESS retryable=false materialId=$materialId" in it })
            assertTrue(events.any { "stage=INIT outcome=SUCCESS retryable=false materialId=$materialId" in it })
            assertTrue(
                events.any {
                    "stage=PART outcome=FAILURE retryable=true materialId=$materialId part=1 partCount=3" in it
                },
            )
            assertTrue(events.any { "stage=COMPLETE outcome=SUCCESS" in it })
            assertTrue(events.any { "stage=VERIFY outcome=SUCCESS" in it })
            assertTrue(events.none { "lecture.mp4" in it || "/tmp/" in it })
        }

    @Test
    fun `an already-synced material is a no-op that never re-uploads`() =
        runBlocking {
            materialRepository.save(
                material().copy(sync = SyncState.Synced, remoteKey = "materials/${contentHash.hex}"),
            )
            objectStore.seedStoredObject(ObjectKey.ofMaterial(contentHash), totalBytes, contentHash)

            val outcome = engine.upload("m1")

            assertEquals(UploadOutcome.Synced, outcome)
            assertTrue(objectStore.uploadedPartNumbers.isEmpty(), "an already-synced material must not be re-uploaded")
        }

    @Test
    fun `a synced material whose object is missing is uploaded again`() =
        runBlocking {
            // The corruption a build that "uploaded" into an in-process map left behind: the row
            // says Synced and carries a key, and the bytes behind that key never existed.
            materialRepository.save(
                material().copy(sync = SyncState.Synced, remoteKey = "materials/${contentHash.hex}"),
            )

            val outcome = engine.upload("m1")

            assertEquals(UploadOutcome.Synced, outcome)
            assertEquals(
                listOf(1, 2, 3),
                objectStore.uploadedPartNumbers,
                "a material the store has never heard of has to be sent, not presented as stored",
            )
        }

    @Test
    fun `a synced material with no local copy and no stored object stops claiming to be stored`() =
        runBlocking {
            materialRepository.save(
                material().copy(
                    sync = SyncState.Synced,
                    remoteKey = "materials/${contentHash.hex}",
                    localPath = null,
                ),
            )

            val outcome = engine.upload("m1")

            assertTrue(outcome is UploadOutcome.Permanent, "there is nothing left to upload: \$outcome")
            val updated = materialRepository.observeById("m1").first()
            assertEquals(null, updated?.remoteKey, "a key that resolves to nothing must not survive")
            assertTrue(
                updated?.sync is SyncState.Failed && !(updated.sync as SyncState.Failed).retryable,
                "retrying cannot conjure bytes that exist in neither place",
            )
        }

    @Test
    fun `a file the store already holds is re-linked without sending a byte`() =
        runBlocking {
            materialRepository.save(material())
            objectStore.seedStoredObject(ObjectKey.ofMaterial(contentHash), totalBytes, contentHash)

            val outcome = engine.upload("m1")

            assertEquals(UploadOutcome.Synced, outcome)
            assertTrue(
                objectStore.uploadedPartNumbers.isEmpty(),
                "content addressing means identical bytes cost no storage and no bandwidth twice",
            )
            val updated = materialRepository.observeById("m1").first()
            assertEquals(SyncState.Synced, updated?.sync)
            assertEquals("materials/${contentHash.hex}", updated?.remoteKey)
        }

    @Test
    fun `an object of the wrong size is uploaded rather than adopted`() =
        runBlocking {
            materialRepository.save(material())
            objectStore.seedStoredObject(ObjectKey.ofMaterial(contentHash), totalBytes - 1, contentHash)

            val outcome = engine.upload("m1")

            assertEquals(UploadOutcome.Synced, outcome)
            assertEquals(listOf(1, 2, 3), objectStore.uploadedPartNumbers, "a truncated object is not a valid re-link")
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
