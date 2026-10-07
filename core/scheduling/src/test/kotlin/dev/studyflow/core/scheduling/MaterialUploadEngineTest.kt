package dev.studyflow.core.scheduling

import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.common.logging.DiagnosticThrowableKind
import dev.studyflow.core.domain.materials.CloudStorageLimits
import dev.studyflow.core.domain.materials.CompletedUploadPart
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStoreException
import dev.studyflow.core.storage.presigned.StorageFunctionUrlSource
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.data.FakeUploadProgressStore
import dev.studyflow.core.testing.logging.RecordingAppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Base64
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
                    etag = "\"etag-1\"",
                    sizeBytes =
                        8 * 1024 * 1024,
                    uploadId = "upload-$key",
                ),
            )

            val outcome = engine.upload("m1")

            assertEquals(UploadOutcome.Synced, outcome)
            assertFalse(1 in objectStore.uploadedPartNumbers, "part 1 was already acknowledged and must not be re-sent")
            assertEquals(listOf(2, 3), objectStore.uploadedPartNumbers)
            assertEquals("\"etag-1\"", objectStore.completedParts.single { it.number == 1 }.etag)
            assertEquals(SyncState.Synced, materialRepository.observeById("m1").first()?.sync)
        }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["expired-session"])
    fun `replacement sessions and legacy receipts re-send every part without stale etags`(oldUploadId: String?) =
        runBlocking {
            materialRepository.save(material())
            listOf(1, 2, 3, 99).forEach { number ->
                uploadProgressStore.recordCompletedPart(
                    "m1",
                    CompletedUploadPart(number, "stale-$number", 8 * 1024 * 1024, oldUploadId),
                )
            }
            objectStore.failCompleteUpload = ObjectStoreException.Transient("completion interrupted")

            assertInstanceOf(UploadOutcome.Retryable::class.java, engine.upload("m1"))

            assertEquals(listOf(1, 2, 3), objectStore.uploadedPartNumbers)
            val receipts = uploadProgressStore.completedParts("m1")
            assertEquals(listOf(1, 2, 3), receipts.map { it.number }.sorted())
            assertTrue(receipts.all { it.uploadId == "upload-${ObjectKey.ofMaterial(contentHash)}" })
            assertTrue(receipts.none { it.etag.startsWith("stale-") })

            objectStore.failCompleteUpload = null
            assertEquals(UploadOutcome.Synced, engine.upload("m1"))
            assertEquals(
                listOf(1, 2, 3),
                objectStore.uploadedPartNumbers,
                "same-session completion retries send no parts",
            )
            assertTrue(objectStore.completedParts.none { it.etag.startsWith("stale-") })
            assertTrue(uploadProgressStore.completedParts("m1").isEmpty())
        }

    @Test
    fun `invalid parts after sending bytes clears receipts and retries a full transfer`() =
        runBlocking {
            materialRepository.save(material())
            objectStore.failCompleteUpload = ObjectStoreException.InvalidParts("invalid_parts")

            assertInstanceOf(UploadOutcome.Retryable::class.java, engine.upload("m1"))
            assertTrue(uploadProgressStore.completedParts("m1").isEmpty())
            val sync = materialRepository.observeById("m1").first()?.sync
            assertTrue(sync is SyncState.Failed && sync.retryable)

            objectStore.failCompleteUpload = null
            assertEquals(UploadOutcome.Synced, engine.upload("m1"))
            assertEquals(listOf(1, 2, 3, 1, 2, 3), objectStore.uploadedPartNumbers)
        }

    @ParameterizedTest
    @ValueSource(strings = ["invalid_parts", "storage_unavailable"])
    fun `completion with dead provider receipts clears them before retry and sends every part`(code: String) =
        runBlocking {
            val materialId = "00000000-0000-0000-0000-000000000001"
            materialRepository.save(material().copy(id = materialId))
            val logger = RecordingAppLogger()
            val loggedEngine =
                MaterialUploadEngine(
                    materialRepository,
                    uploadProgressStore,
                    objectStore,
                    readPart = { _, part -> ByteArray(part.size.toInt()) },
                    logger = logger,
                )
            val uploadId = "upload-${ObjectKey.ofMaterial(contentHash)}"
            listOf(1, 2, 3).forEach { number ->
                val size = if (number == 3) 4L * 1024 * 1024 else 8L * 1024 * 1024
                uploadProgressStore.recordCompletedPart(
                    materialId,
                    CompletedUploadPart(number, "dead-provider-etag-$number", size, uploadId),
                )
            }
            objectStore.failCompleteUpload =
                if (code == "invalid_parts") {
                    ObjectStoreException.InvalidParts(code)
                } else {
                    ObjectStoreException.Transient(code)
                }

            assertInstanceOf(UploadOutcome.Retryable::class.java, loggedEngine.upload(materialId))
            assertTrue(objectStore.uploadedPartNumbers.isEmpty(), "the first attempt must reproduce the no-PART path")
            assertTrue(uploadProgressStore.completedParts(materialId).isEmpty())

            objectStore.failCompleteUpload = null
            assertEquals(UploadOutcome.Synced, loggedEngine.upload(materialId))
            assertEquals(listOf(1, 2, 3), objectStore.uploadedPartNumbers)
            assertTrue(objectStore.completedParts.none { it.etag.startsWith("dead-provider-") })
            assertEquals(SyncState.Synced, materialRepository.observeById(materialId).first()?.sync)
            assertTrue(logger.entries.any { it.message.contains("stage=COMPLETE outcome=SUCCESS") })
        }

    @Test
    fun `stale receipt cleanup preserves current-session receipts before a part failure`() =
        runBlocking {
            materialRepository.save(material())
            val key = ObjectKey.ofMaterial(contentHash)
            val currentReceipt = CompletedUploadPart(1, "etag-1", 8 * 1024 * 1024, "upload-$key")
            objectStore.seedAcknowledgedPart(key, number = 1, size = currentReceipt.sizeBytes)
            uploadProgressStore.recordCompletedPart("m1", currentReceipt)
            uploadProgressStore.recordCompletedPart("m1", CompletedUploadPart(2, "stale-2", 8 * 1024 * 1024))
            objectStore.failNextUploadPart = ObjectStoreException.Transient("connection reset")

            assertInstanceOf(UploadOutcome.Retryable::class.java, engine.upload("m1"))
            assertEquals(listOf(currentReceipt), uploadProgressStore.completedParts("m1"))
            assertEquals(listOf(2), objectStore.uploadedPartNumbers)

            assertEquals(UploadOutcome.Synced, engine.upload("m1"))
            assertEquals(listOf(2, 2, 3), objectStore.uploadedPartNumbers)
        }

    @Test
    fun `a cancelled worker durably records the session before progress notification and resumes`() =
        runBlocking {
            materialRepository.save(material())
            val interruptedEngine =
                MaterialUploadEngine(
                    materialRepository,
                    uploadProgressStore,
                    objectStore,
                    readPart = { _, part -> ByteArray(part.size.toInt()) },
                    onProgress = { _, _ -> throw CancellationException("worker stopped") },
                )

            assertThrows(CancellationException::class.java) { runBlocking { interruptedEngine.upload("m1") } }
            val receipt = uploadProgressStore.completedParts("m1").single()
            assertEquals(1, receipt.number)
            assertEquals("upload-${ObjectKey.ofMaterial(contentHash)}", receipt.uploadId)

            assertEquals(UploadOutcome.Synced, engine.upload("m1"))
            assertEquals(listOf(1, 2, 3), objectStore.uploadedPartNumbers)
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
    fun `a permanent init conflict is surfaced and parked rather than retried forever`() =
        runBlocking {
            materialRepository.save(material())
            val reason = "initUpload failed (409 object_already_exists)"
            objectStore.failInitUpload = ObjectStoreException.AccessDenied(reason)

            assertEquals(UploadOutcome.Permanent(reason), engine.upload("m1"))
            assertEquals(
                SyncState.Failed(reason, retryable = false),
                materialRepository.observeById("m1").first()?.sync,
            )
            assertTrue(objectStore.uploadedPartNumbers.isEmpty())
        }

    @Test
    fun `an active init claim remains retryable until completion or lease recovery`() =
        runBlocking {
            materialRepository.save(material())
            val reason = "initUpload failed (409 upload_in_progress)"
            objectStore.failInitUpload = ObjectStoreException.Transient(reason)

            assertEquals(UploadOutcome.Retryable(reason), engine.upload("m1"))
            assertEquals(
                SyncState.Failed(reason, retryable = true),
                materialRepository.observeById("m1").first()?.sync,
            )
            assertTrue(objectStore.uploadedPartNumbers.isEmpty())
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

            val failure =
                logger.diagnosticsWith(DiagnosticCode.MaterialUpload).single {
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

            val failure =
                logger.diagnosticsWith(DiagnosticCode.MaterialUpload).single {
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
                ObjectStoreException.InvalidParts("invalid parts") to DiagnosticThrowableKind.OBJECT_ACCESS_DENIED,
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

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(longs = [1_000, 8L * 1024 * 1024, 16L * 1024 * 1024, 20L * 1024 * 1024])
    fun `initUpload receives one base64 SHA-256 per part, of exactly that part's bytes`(sizeBytes: Long) =
        runBlocking {
            // Bytes that differ between parts, so a checksum of the wrong slice cannot pass.
            val content = ByteArray(sizeBytes.toInt()) { index -> (index % 251).toByte() }
            materialRepository.save(material().copy(sizeBytes = sizeBytes))
            val checksummingEngine =
                MaterialUploadEngine(
                    materialRepository = materialRepository,
                    uploadProgressStore = uploadProgressStore,
                    objectStore = objectStore,
                    readPart = { _, part -> content.copyOfRange(part.offset.toInt(), part.endExclusive.toInt()) },
                )

            assertEquals(UploadOutcome.Synced, checksummingEngine.upload("m1"))

            val partSize = CloudStorageLimits.PART_SIZE_BYTES.toInt()
            val expected =
                (0 until content.size step partSize).map { start ->
                    val slice = content.copyOfRange(start, minOf(content.size, start + partSize))
                    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(slice))
                }
            val checksums = objectStore.initRequests.single().partChecksums
            assertEquals(StorageFunctionUrlSource.expectedPartCount(sizeBytes), checksums.size)
            assertEquals(expected, checksums)
        }

    @Test
    fun `an unexpected exception is logged as a failure of its stage and parks the material`() =
        runBlocking {
            val logger = RecordingAppLogger()
            val materialId = "00000000-0000-0000-0000-000000000005"
            materialRepository.save(material().copy(id = materialId))
            objectStore.failInitUpload = IllegalStateException("programming error")
            val diagnosticEngine =
                MaterialUploadEngine(
                    materialRepository = materialRepository,
                    uploadProgressStore = uploadProgressStore,
                    objectStore = objectStore,
                    readPart = { _, part -> ByteArray(part.size.toInt()) },
                    logger = logger,
                )

            assertInstanceOf(UploadOutcome.Permanent::class.java, diagnosticEngine.upload(materialId))

            val failure =
                logger.diagnosticsWith(DiagnosticCode.MaterialUpload).single { "outcome=FAILURE" in it }
            assertTrue("stage=INIT outcome=FAILURE retryable=false" in failure, failure)
            assertTrue("throwable=UNEXPECTED" in failure, failure)
            val sync = materialRepository.observeById(materialId).first()?.sync
            assertTrue(sync is SyncState.Failed && !sync.retryable, "an unexpected failure must not retry forever")
        }

    @Test
    fun `cancellation propagates rather than being recorded as a failure`() {
        materialRepository.saveBlocking(material())
        objectStore.failInitUpload = CancellationException("worker stopped")

        assertThrows(CancellationException::class.java) { runBlocking { engine.upload("m1") } }

        val sync = runBlocking { materialRepository.observeById("m1").first()?.sync }
        assertFalse(sync is SyncState.Failed, "a cancelled upload is not a failed one")
    }

    private fun FakeMaterialRepository.saveBlocking(material: Material) = runBlocking { save(material) }

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
