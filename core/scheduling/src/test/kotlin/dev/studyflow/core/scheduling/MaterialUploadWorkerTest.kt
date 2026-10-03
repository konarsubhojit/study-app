package dev.studyflow.core.scheduling

import androidx.work.ListenableWorker
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.storage.ObjectStoreException
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.data.FakeUploadProgressStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class MaterialUploadWorkerTest {
    @Test
    fun `a permanent storage failure returns a terminal worker result`() =
        runTest {
            val materialId = "00000000-0000-0000-0000-000000000005"
            val repository = FakeMaterialRepository()
            val store =
                RecordingObjectStore().apply {
                    failStat = ObjectStoreException.AccessDenied("stat rejected with invalid_request")
                }
            repository.save(
                Material(
                    id = materialId,
                    displayName = "material.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 1024,
                    contentHash = ContentHash("d".repeat(64)),
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    localPath = "/private/material.pdf",
                ),
            )
            val engine =
                MaterialUploadEngine(
                    materialRepository = repository,
                    uploadProgressStore = FakeUploadProgressStore(),
                    objectStore = store,
                )

            val outcome = engine.upload(materialId)
            assertInstanceOf(UploadOutcome.Permanent::class.java, outcome)

            val result = outcome.toWorkerResult()

            assertEquals(ListenableWorker.Result.failure(), result)
            assertFalse(result == ListenableWorker.Result.retry())
        }

    @Test
    fun `worker result mapping preserves retry and success outcomes`() {
        assertEquals(ListenableWorker.Result.success(), UploadOutcome.Synced.toWorkerResult())
        assertEquals(
            ListenableWorker.Result.retry(),
            UploadOutcome.Retryable("temporarily unavailable").toWorkerResult(),
        )
        assertEquals(ListenableWorker.Result.failure(), UploadOutcome.MaterialMissing.toWorkerResult())
    }

    @Test
    fun `notification denial during foreground promotion does not block the upload`() =
        runTest {
            val materialId = "00000000-0000-0000-0000-000000000004"
            val repository = FakeMaterialRepository()
            val store = RecordingObjectStore()
            repository.save(
                Material(
                    id = materialId,
                    displayName = "material.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 1024,
                    contentHash = ContentHash("d".repeat(64)),
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    localPath = "/private/material.pdf",
                ),
            )
            val engine =
                MaterialUploadEngine(
                    materialRepository = repository,
                    uploadProgressStore = FakeUploadProgressStore(),
                    objectStore = store,
                    readPart = { _, part -> ByteArray(part.size.toInt()) },
                )

            val outcome =
                runAfterForegroundPromotion(
                    promoteToForeground = { throw SecurityException("notifications denied") },
                    onPromotionUnavailable = {},
                    work = { engine.upload(materialId) },
                )

            assertEquals(UploadOutcome.Synced, outcome)
            assertEquals(listOf(1), store.uploadedPartNumbers)
            assertEquals(SyncState.Synced, repository.observeById(materialId).first()?.sync)
        }

    @Test
    fun `other foreground promotion failures do not block the upload`() =
        runTest {
            var workStarted = false
            var promotionFailure: Exception? = null

            val result =
                runAfterForegroundPromotion(
                    promoteToForeground = { throw UnsupportedOperationException("foreground unavailable") },
                    onPromotionUnavailable = { promotionFailure = it },
                    work = {
                        workStarted = true
                        "uploaded"
                    },
                )

            assertEquals("uploaded", result)
            assertTrue(workStarted)
            assertTrue(promotionFailure is UnsupportedOperationException)
        }

    @Test
    fun `foreground promotion cancellation is propagated without starting the upload`() =
        runTest {
            var workStarted = false
            var cancellation: CancellationException? = null

            try {
                runAfterForegroundPromotion(
                    promoteToForeground = { throw CancellationException("worker stopped") },
                    onPromotionUnavailable = {},
                    work = { workStarted = true },
                )
            } catch (failure: CancellationException) {
                cancellation = failure
            }

            assertNotNull(cancellation)
            assertFalse(workStarted)
        }
}
