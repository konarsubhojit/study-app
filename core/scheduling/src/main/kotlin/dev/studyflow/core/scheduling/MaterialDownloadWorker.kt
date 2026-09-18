package dev.studyflow.core.scheduling

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
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
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            val materialId = inputData.getString(EXTRA_DOWNLOAD_MATERIAL_ID)
            if (materialId.isNullOrBlank()) {
                Log.w(TAG, "Download work is missing its material id; dropping it")
                return Result.failure()
            }

            val engine =
                MaterialDownloadEngine(
                    materialRepository = materialRepository,
                    downloadProgressStore = downloadProgressStore,
                    objectStore = objectStore,
                    destinationPath = { material ->
                        File(File(applicationContext.filesDir, MATERIALS_DIRECTORY_NAME), materialDownloadCacheFileName(material))
                            .absolutePath
                    },
                    transport = transport,
                )
            return when (engine.download(materialId)) {
                is DownloadOutcome.Cached -> Result.success()
                is DownloadOutcome.Retryable -> Result.retry()
                is DownloadOutcome.Permanent -> Result.failure()
                DownloadOutcome.MaterialMissing -> Result.failure()
            }
        }

        private companion object {
            const val TAG = "MaterialDownloadWorker"
            const val MATERIALS_DIRECTORY_NAME = "materials"
        }
    }
