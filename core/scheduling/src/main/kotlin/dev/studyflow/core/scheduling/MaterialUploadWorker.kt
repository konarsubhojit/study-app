package dev.studyflow.core.scheduling

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.domain.materials.UploadProgressStore
import dev.studyflow.core.notifications.NotificationAction
import dev.studyflow.core.notifications.NotificationProgress
import dev.studyflow.core.notifications.StudyFlowNotificationChannel
import dev.studyflow.core.notifications.StudyFlowNotificationFactory
import dev.studyflow.core.notifications.StudyFlowNotifier
import dev.studyflow.core.storage.ObjectStore

/** The material id an upload work request carries in its input data. */
public const val EXTRA_UPLOAD_MATERIAL_ID: String = "dev.studyflow.core.scheduling.UPLOAD_MATERIAL_ID"

/** A stable notification id derived from the material id, so progress updates the same row. */
public fun materialUploadNotificationId(materialId: String): Int = "material-upload:$materialId".hashCode()

/**
 * Chunked, resumable material uploads, with a foreground progress notification (issue #38).
 *
 * All of the actual upload logic — which parts to send, when to persist progress, when a failure
 * is worth retrying — lives in [MaterialUploadEngine], built fresh for each run so its progress
 * callback can call back into *this* run's [setForeground] rather than being wired once at
 * injection time. This class only does the platform-facing parts: reading which material to
 * upload from [WorkerParameters.getInputData], keeping the transfer foreground so the platform's
 * background execution limits do not kill a multi-hundred-megabyte transfer outright, and
 * translating [UploadOutcome] into the [Result] WorkManager's own backoff and constraints act on.
 */
@HiltWorker
public class MaterialUploadWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted parameters: WorkerParameters,
        private val materialRepository: MaterialRepository,
        private val uploadProgressStore: UploadProgressStore,
        private val objectStore: ObjectStore,
        private val notificationFactory: StudyFlowNotificationFactory,
        private val notifier: StudyFlowNotifier,
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            val materialId = inputData.getString(EXTRA_UPLOAD_MATERIAL_ID)
            if (materialId.isNullOrBlank()) {
                Log.w(TAG, "Upload work is missing its material id; dropping it")
                return Result.failure()
            }

            setForeground(foregroundInfo(materialId, NotificationProgress.Indeterminate))

            val engine =
                MaterialUploadEngine(
                    materialRepository = materialRepository,
                    uploadProgressStore = uploadProgressStore,
                    objectStore = objectStore,
                    onProgress = { uploadedBytes, totalBytes ->
                        setForeground(foregroundInfo(materialId, percentProgress(uploadedBytes, totalBytes)))
                    },
                )

            return when (val outcome = engine.upload(materialId)) {
                UploadOutcome.Synced -> {
                    notifier.cancel(materialUploadNotificationId(materialId))
                    Result.success()
                }

                is UploadOutcome.Retryable -> {
                    notifier.post(
                        materialUploadNotificationId(materialId),
                        StudyFlowNotificationChannel.UPLOADS,
                        finishedNotification(outcome.reason),
                    )
                    Result.retry()
                }

                is UploadOutcome.Permanent -> {
                    notifier.post(
                        materialUploadNotificationId(materialId),
                        StudyFlowNotificationChannel.UPLOADS,
                        finishedNotification(outcome.reason),
                    )
                    Result.failure()
                }

                UploadOutcome.MaterialMissing -> {
                    notifier.cancel(materialUploadNotificationId(materialId))
                    Result.failure()
                }
            }
        }

        private fun foregroundInfo(
            materialId: String,
            progress: NotificationProgress,
        ): ForegroundInfo {
            val notification =
                notificationFactory.progress(
                    title = "Uploading material",
                    text = "Your file is being uploaded in the background.",
                    progress = progress,
                    contentIntent = null,
                    actions = listOf(cancelAction()),
                )
            return ForegroundInfo(
                materialUploadNotificationId(materialId),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }

        /**
         * [NotificationProgress.Determinate] takes an `Int` completed-of-total pair; a byte count
         * can exceed that range for a very large file, so it is scaled down to a stable percentage
         * instead of truncated.
         */
        private fun percentProgress(
            uploadedBytes: Long,
            totalBytes: Long,
        ): NotificationProgress.Determinate {
            if (totalBytes <= 0L) return NotificationProgress.Determinate(completed = 1, total = 1)
            val percent = ((uploadedBytes * PERCENT_TOTAL) / totalBytes).toInt().coerceIn(0, PERCENT_TOTAL)
            return NotificationProgress.Determinate(completed = percent, total = PERCENT_TOTAL)
        }

        private fun finishedNotification(reason: String): Notification =
            notificationFactory.progress(
                title = "Upload paused",
                text = reason,
                progress = NotificationProgress.Finished,
                contentIntent = null,
            )

        private fun cancelAction(): NotificationAction =
            NotificationAction(
                title = "Cancel",
                icon = android.R.drawable.ic_menu_close_clear_cancel,
                intent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )

        private companion object {
            const val TAG = "MaterialUploadWorker"
            const val PERCENT_TOTAL = 100
        }
    }
