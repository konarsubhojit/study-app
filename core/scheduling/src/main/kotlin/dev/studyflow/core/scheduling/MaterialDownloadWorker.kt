package dev.studyflow.core.scheduling

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.domain.materials.DownloadProgressStore
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.storage.ObjectStore
import java.io.File

public const val EXTRA_DOWNLOAD_MATERIAL_ID: String = "dev.studyflow.core.scheduling.DOWNLOAD_MATERIAL_ID"

@HiltWorker
public class MaterialDownloadWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted parameters: WorkerParameters,
        private val materialRepository: MaterialRepository,
        private val downloadProgressStore: DownloadProgressStore,
        private val objectStore: ObjectStore,
        private val transport: DownloadTransport,
        private val logger: AppLogger,
        private val remoteVerifier: MaterialRemoteVerifier,
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            val materialId = inputData.getString(EXTRA_DOWNLOAD_MATERIAL_ID)
            if (materialId.isNullOrBlank()) {
                logger.warning(TAG, "Download work is missing its material id; dropping it")
                return Result.failure()
            }

            val engine =
                MaterialDownloadEngine(
                    materialRepository = materialRepository,
                    downloadProgressStore = downloadProgressStore,
                    objectStore = objectStore,
                    logger = logger,
                    destinationPath = { material ->
                        val cacheDirectory = File(applicationContext.filesDir, MATERIALS_DIRECTORY_NAME)
                        File(cacheDirectory, materialDownloadCacheFileName(material)).absolutePath
                    },
                    transport = transport,
                    onRemoteMissing = remoteVerifier::onRemoteMissing,
                )
            return engine.download(materialId).toWorkerResult(runAttemptCount)
        }

        private companion object {
            const val TAG = "MaterialDownloadWorker"
            const val MATERIALS_DIRECTORY_NAME = "materials"
        }
    }

internal fun DownloadOutcome.toWorkerResult(runAttemptCount: Int): ListenableWorker.Result =
    when (this) {
        is DownloadOutcome.Cached -> ListenableWorker.Result.success()
        is DownloadOutcome.Retryable -> retryWhileAttemptsRemain(runAttemptCount)
        is DownloadOutcome.Permanent, DownloadOutcome.MaterialMissing -> ListenableWorker.Result.failure()
    }
