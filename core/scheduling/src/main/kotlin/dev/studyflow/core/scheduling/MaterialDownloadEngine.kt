package dev.studyflow.core.scheduling

import dev.studyflow.core.domain.materials.DownloadProgress
import dev.studyflow.core.domain.materials.DownloadProgressStore
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.model.Material
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.storage.ObjectStoreException
import dev.studyflow.core.storage.PresignedUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException

public sealed interface DownloadOutcome {
    public data class Cached(
        val localPath: String,
    ) : DownloadOutcome

    public data class Retryable(
        val reason: String,
    ) : DownloadOutcome

    public data class Permanent(
        val reason: String,
    ) : DownloadOutcome

    public data object MaterialMissing : DownloadOutcome
}

public fun materialDownloadCacheFileName(material: Material): String = "material-${material.contentHash.hex}"

/**
 * Resumable material download runner, independent of WorkManager so restart/resume rules are unit
 * testable (issue #39).
 */
public class MaterialDownloadEngine(
    private val materialRepository: MaterialRepository,
    private val downloadProgressStore: DownloadProgressStore,
    private val objectStore: ObjectStore,
    private val destinationPath: (Material) -> String,
    private val transport: DownloadTransport,
) {
    public suspend fun download(materialId: String): DownloadOutcome {
        val material = materialRepository.observeByIdOnce(materialId) ?: return DownloadOutcome.MaterialMissing
        material.localPath?.let { return DownloadOutcome.Cached(it) }

        val remoteKey = material.remoteKey
        if (remoteKey.isNullOrBlank()) {
            return DownloadOutcome.Permanent("material has no remote object to download")
        }

        return try {
            val progress = downloadProgressStore.progress(materialId)
            val startAt = progress?.downloadedBytes ?: 0L
            val totalBytes = progress?.totalBytes ?: material.sizeBytes
            val url = objectStore.getDownloadUrl(ObjectKey(remoteKey))
            val localPath = destinationPath(material)
            val result =
                transport.download(
                    request =
                        DownloadRequest(
                            url = url,
                            localPath = localPath,
                            rangeStart = startAt,
                            expectedTotalBytes = totalBytes,
                        ),
                ) { downloadedBytes, reportedTotalBytes ->
                    downloadProgressStore.save(
                        DownloadProgress(
                            materialId = materialId,
                            downloadedBytes = downloadedBytes,
                            totalBytes = reportedTotalBytes ?: totalBytes,
                        ),
                    )
                }
            val completedBytes = result.downloadedBytes
            if (completedBytes != material.sizeBytes) {
                return DownloadOutcome.Permanent("downloaded $completedBytes bytes but expected ${material.sizeBytes}")
            }
            materialRepository.save(material.copy(localPath = localPath))
            downloadProgressStore.clear(materialId)
            DownloadOutcome.Cached(localPath)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ObjectStoreException) {
            val reason = exception.message ?: exception::class.simpleName.orEmpty()
            if (exception.retryable) DownloadOutcome.Retryable(reason) else DownloadOutcome.Permanent(reason)
        } catch (exception: IOException) {
            DownloadOutcome.Retryable("download interrupted: ${exception.message}")
        }
    }
}

public data class DownloadRequest(
    val url: PresignedUrl,
    val localPath: String,
    val rangeStart: Long,
    val expectedTotalBytes: Long,
) {
    init {
        require(localPath.isNotBlank()) { "localPath must not be blank" }
        require(rangeStart >= 0) { "rangeStart must not be negative, was $rangeStart" }
        require(expectedTotalBytes >= rangeStart) { "expectedTotalBytes must be at least rangeStart" }
    }
}

public data class DownloadResult(
    val downloadedBytes: Long,
    val totalBytes: Long?,
) {
    init {
        require(downloadedBytes >= 0) { "downloadedBytes must not be negative, was $downloadedBytes" }
        require(totalBytes == null || totalBytes >= downloadedBytes) {
            "totalBytes must be null or at least downloadedBytes"
        }
    }
}

public fun interface DownloadTransport {
    public suspend fun download(
        request: DownloadRequest,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult
}

private suspend fun MaterialRepository.observeByIdOnce(materialId: String): Material? =
    observeById(materialId).first()
