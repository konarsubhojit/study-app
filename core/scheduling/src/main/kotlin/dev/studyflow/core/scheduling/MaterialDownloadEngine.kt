package dev.studyflow.core.scheduling

import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.DiagnosticCode
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
    private val logger: AppLogger? = null,
) {
    public suspend fun download(materialId: String): DownloadOutcome {
        var stage = MaterialTransferStage.PLAN
        val material = materialRepository.observeByIdOnce(materialId)
        if (material == null) {
            log(materialId, stage, MaterialTransferOutcome.FAILURE, retryable = false)
            return DownloadOutcome.MaterialMissing
        }
        material.localPath?.let { localPath ->
            log(materialId, MaterialTransferStage.VERIFY, MaterialTransferOutcome.SKIPPED, retryable = false)
            return DownloadOutcome.Cached(localPath)
        }
        val remoteKey = material.remoteKey?.takeIf(String::isNotBlank)
        if (remoteKey == null) {
            log(materialId, stage, MaterialTransferOutcome.FAILURE, retryable = false)
            return DownloadOutcome.Permanent("material has no remote object to download")
        }
        return try {
            log(materialId, stage, MaterialTransferOutcome.STARTED, retryable = true)
            log(materialId, stage, MaterialTransferOutcome.SUCCESS, retryable = false)
            stage = MaterialTransferStage.INIT
            log(materialId, stage, MaterialTransferOutcome.STARTED, retryable = true)
            val url = objectStore.getDownloadUrl(ObjectKey(remoteKey))
            log(materialId, stage, MaterialTransferOutcome.SUCCESS, retryable = false)

            val progress = downloadProgressStore.progress(materialId)
            val startAt = progress?.downloadedBytes ?: 0L
            val totalBytes = progress?.totalBytes ?: material.sizeBytes
            val localPath = destinationPath(material)
            stage = MaterialTransferStage.PART
            log(materialId, stage, MaterialTransferOutcome.STARTED, retryable = true)
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
            log(materialId, stage, MaterialTransferOutcome.SUCCESS, retryable = false)
            stage = MaterialTransferStage.COMPLETE
            log(materialId, stage, MaterialTransferOutcome.SUCCESS, retryable = false)
            stage = MaterialTransferStage.VERIFY
            log(materialId, stage, MaterialTransferOutcome.STARTED, retryable = true)
            if (result.downloadedBytes != material.sizeBytes) {
                log(materialId, stage, MaterialTransferOutcome.FAILURE, retryable = false)
                return DownloadOutcome.Permanent(
                    "downloaded ${result.downloadedBytes} bytes but expected ${material.sizeBytes}",
                )
            }
            log(materialId, stage, MaterialTransferOutcome.SUCCESS, retryable = false)
            materialRepository.save(material.copy(localPath = localPath))
            downloadProgressStore.clear(materialId)
            DownloadOutcome.Cached(localPath)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ObjectStoreException) {
            val reason = exception.message ?: exception::class.simpleName.orEmpty()
            log(materialId, stage, MaterialTransferOutcome.FAILURE, exception.retryable, throwable = exception)
            if (exception.retryable) DownloadOutcome.Retryable(reason) else DownloadOutcome.Permanent(reason)
        } catch (exception: IOException) {
            log(materialId, stage, MaterialTransferOutcome.FAILURE, retryable = true, throwable = exception)
            DownloadOutcome.Retryable("download interrupted: ${exception.message}")
        }
    }

    private fun log(
        materialId: String,
        stage: MaterialTransferStage,
        outcome: MaterialTransferOutcome,
        retryable: Boolean,
        throwable: Throwable? = null,
    ) {
        logger?.materialTransfer(
            code = DiagnosticCode.MaterialDownload,
            materialId = materialId,
            stage = stage,
            outcome = outcome,
            retryable = retryable,
            throwable = throwable,
        )
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

private suspend fun MaterialRepository.observeByIdOnce(materialId: String): Material? = observeById(materialId).first()
