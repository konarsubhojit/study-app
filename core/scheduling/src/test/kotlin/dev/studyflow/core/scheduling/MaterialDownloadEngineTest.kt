package dev.studyflow.core.scheduling

import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.domain.materials.DownloadProgress
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStoreException
import dev.studyflow.core.testing.data.FakeDownloadProgressStore
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.logging.RecordingAppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Instant

@DisplayName("MaterialDownloadEngine")
class MaterialDownloadEngineTest {
    private val materialRepository = FakeMaterialRepository()
    private val progressStore = FakeDownloadProgressStore()
    private val objectStore = RecordingObjectStore()
    private val transport = RecordingDownloadTransport()
    private val engine =
        MaterialDownloadEngine(
            materialRepository = materialRepository,
            downloadProgressStore = progressStore,
            objectStore = objectStore,
            destinationPath = { "/cache/${materialDownloadCacheFileName(it)}" },
            transport = transport,
        )

    @Test
    fun `cached material opens without downloading`() =
        runBlocking {
            materialRepository.save(material().copy(localPath = "/cache/existing"))

            val outcome = engine.download("m1")

            assertEquals(DownloadOutcome.Cached("/cache/existing"), outcome)
            assertEquals(emptyList<Long>(), transport.rangeStarts)
        }

    @Test
    fun `download resumes from the persisted byte offset after interruption`() =
        runBlocking {
            materialRepository.save(material())
            transport.failAfterProgress = IOException("connection lost")

            val firstOutcome = engine.download("m1")

            assertInstanceOf(DownloadOutcome.Retryable::class.java, firstOutcome)
            assertEquals(4L, progressStore.progress("m1")?.downloadedBytes)

            transport.failAfterProgress = null
            val secondOutcome = engine.download("m1")

            assertEquals(DownloadOutcome.Cached("/cache/material-${HASH.hex}"), secondOutcome)
            assertEquals(listOf(0L, 4L), transport.rangeStarts)
            assertEquals("/cache/material-${HASH.hex}", materialRepository.observeById("m1").first()?.localPath)
            assertNull(progressStore.progress("m1"))
        }

    @Test
    fun `missing remote object is permanent and keeps material uncached`() =
        runBlocking {
            materialRepository.save(material())
            objectStore.failDownloadUrl = ObjectStoreException.NotFound(ObjectKey("materials/${HASH.hex}"))

            val outcome = engine.download("m1")

            assertInstanceOf(DownloadOutcome.Permanent::class.java, outcome)
            assertNull(materialRepository.observeById("m1").first()?.localPath)
        }

    @Test
    fun `a missing remote object is reported so the material can be healed, and nothing else is`() =
        runBlocking {
            val missing = mutableListOf<String>()
            val healingEngine =
                MaterialDownloadEngine(
                    materialRepository = materialRepository,
                    downloadProgressStore = progressStore,
                    objectStore = objectStore,
                    destinationPath = { "/cache/${materialDownloadCacheFileName(it)}" },
                    transport = transport,
                    onRemoteMissing = { missing += it },
                )
            materialRepository.save(material())

            objectStore.failDownloadUrl = ObjectStoreException.Transient("offline")
            healingEngine.download("m1")
            assertTrue(missing.isEmpty(), "a transient failure says nothing about whether the object exists")

            objectStore.failDownloadUrl = ObjectStoreException.NotFound(ObjectKey("materials/${HASH.hex}"))
            healingEngine.download("m1")
            assertEquals(listOf("m1"), missing)
        }

    @Test
    fun `download diagnostics identify the failed transfer stage without file paths`() =
        runBlocking {
            val logger = RecordingAppLogger()
            val materialId = "00000000-0000-0000-0000-000000000002"
            materialRepository.save(material().copy(id = materialId))
            transport.failAfterProgress = IOException("connection lost")
            val diagnosticEngine =
                MaterialDownloadEngine(
                    materialRepository = materialRepository,
                    downloadProgressStore = progressStore,
                    objectStore = objectStore,
                    destinationPath = { "/private/cache/${materialDownloadCacheFileName(it)}" },
                    transport = transport,
                    logger = logger,
                )

            assertInstanceOf(DownloadOutcome.Retryable::class.java, diagnosticEngine.download(materialId))
            transport.failAfterProgress = null
            assertInstanceOf(DownloadOutcome.Cached::class.java, diagnosticEngine.download(materialId))

            val events = logger.diagnosticsWith(DiagnosticCode.MaterialDownload)
            assertTrue(
                events.any { "stage=PART outcome=FAILURE retryable=true materialId=$materialId" in it },
            )
            assertTrue(events.any { "stage=COMPLETE outcome=SUCCESS" in it })
            assertTrue(events.any { "stage=VERIFY outcome=SUCCESS" in it })
            assertTrue(events.none { "/private/cache/" in it || "lecture.pdf" in it })
        }

    private fun material(): Material =
        Material(
            id = "m1",
            displayName = "lecture.pdf",
            mimeType = "application/pdf",
            sizeBytes = 8,
            contentHash = HASH,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            remoteKey = "materials/${HASH.hex}",
            sync = SyncState.Synced,
            localPath = null,
        )

    private class RecordingDownloadTransport : DownloadTransport {
        val rangeStarts = mutableListOf<Long>()
        var failAfterProgress: IOException? = null

        override suspend fun download(
            request: DownloadRequest,
            onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        ): DownloadResult {
            rangeStarts += request.rangeStart
            failAfterProgress?.let { exception ->
                onProgress(4, request.expectedTotalBytes)
                throw exception
            }
            onProgress(request.expectedTotalBytes, request.expectedTotalBytes)
            return DownloadResult(downloadedBytes = request.expectedTotalBytes, totalBytes = request.expectedTotalBytes)
        }
    }

    private companion object {
        val HASH = ContentHash("c".repeat(64))
    }
}
