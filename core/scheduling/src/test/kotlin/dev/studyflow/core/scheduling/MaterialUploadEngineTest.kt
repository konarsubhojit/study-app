package dev.studyflow.core.scheduling

import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.common.logging.DiagnosticThrowableKind
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
import java.io.FileNotFoundException
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
    fun `an unreachable backend parks the upload with a stable non-retryable reason`() =
        runBlocking {
            materialRepository.save(material())
            objectStore.failStat = ObjectStoreException.BackendUnreachable("stat failed: UnknownHostException")

            assertInstanceOf(UploadOutcome.Permanent::class.java, engine.upload("m1"))
            assertEquals(
                SyncState.Failed(SyncState.Failed.BACKEND_UNREACHABLE, retryable = false),
                materialRepository.observeById("m1").first()?.sync,
            )
            assertTrue(objectStore.uploadedPartNumbers.isEmpty())
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
                    "stage=PART outcome=FAILURE retryable=true materialId=$materialId part=1 partCount=3" in it &&
                        "throwable=TRANSIENT_STORAGE" in it &&
                        """throwableMessage="connection reset"""" in it
                },
            )
            assertTrue(events.any { "stage=COMPLETE outcome=SUCCESS" in it })
            assertTrue(events.any { "stage=VERIFY outcome=SUCCESS" in it })
            assertTrue(events.none { "lecture.mp4" in it || "/tmp/" in it })
        }

    @Test
    fun `a dedupe failure logs its message and stable kind`() =
        runBlocking {
            val logger = RecordingAppLogger()
            val materialId = "00000000-0000-0000-0000-000000000003"
            val message = "stat for 'materials/${contentHash.hex}' returned 503 storage_unavailable"
            materialRepository.save(material().copy(id = materialId))
            objectStore.failStat = ObjectStoreException.Transient(message)
            val diagnosticEngine =
                MaterialUploadEngine(
                    materialRepository = materialRepository,
                    uploadProgressStore = uploadProgressStore,
                    objectStore = objectStore,
                    logger = logger,
                )

            assertInstanceOf(UploadOutcome.Retryable::class.java, diagnosticEngine.upload(materialId))

            val failure = logger.diagnosticsWith(DiagnosticCode.MaterialUpload).single {
                "stage=DEDUPE outcome=FAILURE" in it
            }
            assertTrue("throwable=TRANSIENT_STORAGE" in failure)
            assertTrue("""throwableMessage="$message"""" in failure)
            assertFalse("Transient" in failure)
        }

    @Test
    fun `a failure message never logs signed urls tokens or header values`() =
        runBlocking {
            val logger = RecordingAppLogger()
            val materialId = "00000000-0000-0000-0000-000000000004"
            materialRepository.save(material().copy(id = materialId))
            objectStore.failStat =
                ObjectStoreException.Transient(
                    "stat failed https://bucket.example/object?X-Amz-Signature=signed-value&token=eyJ.user.jwt; " +
                        "Authorization: ******; X-Private-Header: header-secret; token=token-secret",
                )
            val diagnosticEngine =
                MaterialUploadEngine(
                    materialRepository = materialRepository,
                    uploadProgressStore = uploadProgressStore,
                    objectStore = objectStore,
                    logger = logger,
                )

            assertInstanceOf(UploadOutcome.Retryable::class.java, diagnosticEngine.upload(materialId))

            val failure = logger.diagnosticsWith(DiagnosticCode.MaterialUpload).single {
                "stage=DEDUPE outcome=FAILURE" in it
            }
            listOf(
                "https://bucket.example/object",
                "signed-value",
                "******",
                "auth-secret",
                "header-secret",
                "token-secret",
                "eyJ.user.jwt",
                "Authorization",
                "X-Private-Header",
                "?",
            ).forEach { assertFalse(it in failure, "diagnostic leaked $it") }
        }

    @Test
    fun `every object store exception subtype maps to its stable failure kind`() {
        val failures =
            listOf(
                ObjectStoreException.NotFound(ObjectKey("materials/${contentHash.hex}")) to
                    DiagnosticThrowableKind.OBJECT_NOT_FOUND,
                ObjectStoreException.AccessDenied("denied") to DiagnosticThrowableKind.OBJECT_ACCESS_DENIED,
                ObjectStoreException.Integrity("integrity failed") to DiagnosticThrowableKind.OBJECT_INTEGRITY,
                ObjectStoreException.QuotaExceeded("quota exceeded") to DiagnosticThrowableKind.OBJECT_QUOTA_EXCEEDED,
                ObjectStoreException.BackendUnreachable("host unreachable") to
                    DiagnosticThrowableKind.BACKEND_UNREACHABLE,
                ObjectStoreException.Transient("temporary failure") to DiagnosticThrowableKind.TRANSIENT_STORAGE,
            )

        failures.forEach { (exception, expected) ->
            assertEquals(expected, exception.materialUploadFailureKind())
            assertEquals(expected.wireName, exception.materialUploadFailureKind().wireName)
        }
    }

    @Test
    fun `the dedupe probe is its own stage and each part starts exactly once`() =
        runBlocking {
            val logger = RecordingAppLogger()
            val materialId = "00000000-0000-0000-0000-000000000002"
            materialRepository.save(material().copy(id = materialId))
            MaterialUploadEngine(
                materialRepository = materialRepository,
                uploadProgressStore = uploadProgressStore,
                objectStore = objectStore,
                readPart = { _, part -> ByteArray(part.size.toInt()) },
                logger = logger,
            ).upload(materialId)

            val events = logger.diagnosticsWith(DiagnosticCode.MaterialUpload)
            val dedupe = events.indexOfFirst { "stage=DEDUPE outcome=NOT_FOUND" in it }
            val planned = events.indexOfFirst { "stage=PLAN outcome=SUCCESS" in it }
            assertTrue(dedupe in 0 until planned, "the dedupe probe must read as happening before planning")
            assertTrue(events.none { "stage=VERIFY outcome=NOT_FOUND" in it })
            val partStarts = events.filter { "stage=PART outcome=STARTED" in it }
            assertEquals(3, partStarts.size, "one PART STARTED per part, never an extra one without a number")
            assertTrue(partStarts.all { "part=" in it })
        }

    @Test
    fun `a material whose file is gone or never staged is re-attach or remove, never retry`() =
        runBlocking {
            materialRepository.save(material())
            val outcome =
                MaterialUploadEngine(
                    materialRepository = materialRepository,
                    uploadProgressStore = uploadProgressStore,
                    objectStore = objectStore,
                    readPart = { path, _ -> throw FileNotFoundException(path) },
                ).upload("m1")
            assertInstanceOf(UploadOutcome.Permanent::class.java, outcome)
            assertTrue(materialRepository.observeById("m1").first()!!.isMissingSource)

            materialRepository.save(material().copy(id = "m2", localPath = null))
            assertInstanceOf(UploadOutcome.Permanent::class.java, engine.upload("m2"))
            assertTrue(materialRepository.observeById("m2").first()!!.isMissingSource)
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
