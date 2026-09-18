package dev.studyflow.core.scheduling

import dev.studyflow.core.domain.materials.DownloadProgress
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStoreException
import dev.studyflow.core.testing.data.FakeDownloadProgressStore
import dev.studyflow.core.testing.data.FakeMaterialRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
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
