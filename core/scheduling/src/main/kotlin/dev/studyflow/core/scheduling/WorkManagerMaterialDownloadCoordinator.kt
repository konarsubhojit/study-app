package dev.studyflow.core.scheduling

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.studyflow.core.domain.materials.MaterialDownloadCoordinator

public fun materialDownloadWorkName(materialId: String): String = "download:$materialId"

public class WorkManagerMaterialDownloadCoordinator(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context),
) : MaterialDownloadCoordinator {
    override suspend fun enqueueDownload(materialId: String) {
        val request =
            OneTimeWorkRequestBuilder<MaterialDownloadWorker>()
                .setInputData(workDataOf(EXTRA_DOWNLOAD_MATERIAL_ID to materialId))
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
        workManager.enqueueUniqueWork(materialDownloadWorkName(materialId), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancelDownload(materialId: String) {
        workManager.cancelUniqueWork(materialDownloadWorkName(materialId))
    }
}
