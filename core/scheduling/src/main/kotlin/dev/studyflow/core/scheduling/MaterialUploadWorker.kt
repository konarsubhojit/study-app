package dev.studyflow.core.scheduling

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.domain.materials.UploadProgressStore
import dev.studyflow.core.domain.sync.SyncScheduler
import dev.studyflow.core.domain.sync.SyncTrigger
import dev.studyflow.core.notifications.NotificationAction
import dev.studyflow.core.notifications.NotificationProgress
import dev.studyflow.core.notifications.StudyFlowNotificationChannel
import dev.studyflow.core.notifications.StudyFlowNotificationFactory
import dev.studyflow.core.notifications.StudyFlowNotifier
import dev.studyflow.core.storage.ObjectStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** The material id an upload work request carries in its input data. */
public const val EXTRA_UPLOAD_MATERIAL_ID: String = "dev.studyflow.core.scheduling.UPLOAD_MATERIAL_ID"

/** A stable notification id derived from the material id, so progress updates the same row. */
public fun materialUploadNotificationId(materialId: String): Int = "material-upload:$materialId".hashCode()

/**
 * How many times a material transfer may run before a still-retryable failure stops being retried.
 *
 * Waiting for a network or a charged battery does not count — WorkManager holds the work without
 * running it — so attempts are only spent on runs that reached the backend and failed. With
 * exponential backoff from WorkManager's minimum, ten attempts span hours, after which a failure
 * that keeps recurring (a backend outage, a misconfigured bucket) stops waking the device; the
 * material keeps its retryable failure state so the user can retry by hand.
 */
internal const val MAX_MATERIAL_TRANSFER_ATTEMPTS: Int = 10

/** [ListenableWorker.Result.retry] while attempts remain, then [ListenableWorker.Result.failure]. */
internal fun retryWhileAttemptsRemain(runAttemptCount: Int): ListenableWorker.Result =
    if (runAttemptCount + 1 < MAX_MATERIAL_TRANSFER_ATTEMPTS) {
        ListenableWorker.Result.retry()
    } else {
        ListenableWorker.Result.failure()
    }

internal suspend fun <T> runAfterForegroundPromotion(
    promoteToForeground: suspend () -> Unit,
    onPromotionUnavailable: (Exception) -> Unit,
    work: suspend () -> T,
): T {
    try {
        promoteToForeground()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (
        @Suppress("TooGenericExceptionCaught") failure: Exception,
    ) {
        onPromotionUnavailable(failure)
    }
    return work()
}

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
// Each dependency is a distinct injected collaborator; WorkManager's assisted factory owns the call.
@Suppress("LongParameterList")
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
        private val syncScheduler: SyncScheduler,
        private val logger: AppLogger,
        private val uploadGate: MaterialUploadGate,
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            val materialId = inputData.getString(EXTRA_UPLOAD_MATERIAL_ID)
            if (materialId.isNullOrBlank()) {
                logger.warning(TAG, "Upload work is missing its material id; dropping it")
                return Result.failure()
            }

            val engine =
                MaterialUploadEngine(
                    materialRepository = materialRepository,
                    uploadProgressStore = uploadProgressStore,
                    objectStore = objectStore,
                    logger = logger,
                    onProgress = { uploadedBytes, totalBytes ->
                        setForegroundSafely(materialId, percentProgress(uploadedBytes, totalBytes))
                    },
                )

            val outcome =
                uploadGate.withPermit {
                    runAfterForegroundPromotion(
                        promoteToForeground = {
                            setForeground(foregroundInfo(materialId, NotificationProgress.Indeterminate))
                        },
                        onPromotionUnavailable = { failure ->
                            logger.warning(
                                TAG,
                                "Upload foreground promotion is unavailable; continuing transfer",
                                failure,
                            )
                        },
                        work = { engine.upload(materialId) },
                    )
                }
            return handleOutcome(materialId, outcome)
        }

        private suspend fun handleOutcome(
            materialId: String,
            outcome: UploadOutcome,
        ): Result =
            when (outcome) {
                UploadOutcome.Synced -> {
                    syncScheduler.requestSync(SyncTrigger.OUTBOUND)
                    notifier.cancel(materialUploadNotificationId(materialId))
                    outcome.toWorkerResult(runAttemptCount)
                }

                is UploadOutcome.Retryable -> {
                    val result = outcome.toWorkerResult(runAttemptCount)
                    val givingUp = result == Result.failure()
                    if (givingUp) {
                        logger.warning(
                            TAG,
                            "Upload kept failing after $MAX_MATERIAL_TRANSFER_ATTEMPTS attempts; stopping",
                        )
                    }
                    notifier.post(
                        materialUploadNotificationId(materialId),
                        StudyFlowNotificationChannel.UPLOADS,
                        if (givingUp) {
                            finishedNotification(
                                title = "Upload failed",
                                message = "You can retry or remove this material.",
                            )
                        } else {
                            finishedNotification(
                                title = "Upload paused",
                                message = "We'll retry when your connection is available.",
                            )
                        },
                    )
                    result
                }

                is UploadOutcome.Permanent -> {
                    val missingSource = materialRepository.observeById(materialId).first()?.isMissingSource == true
                    notifier.post(
                        materialUploadNotificationId(materialId),
                        StudyFlowNotificationChannel.UPLOADS,
                        finishedNotification(
                            title = "Upload failed",
                            message =
                                if (missingSource) {
                                    "This file is no longer on your device. Re-attach it or remove it."
                                } else {
                                    "You can retry or remove this material."
                                },
                        ),
                    )
                    outcome.toWorkerResult(runAttemptCount)
                }

                UploadOutcome.MaterialMissing -> {
                    notifier.cancel(materialUploadNotificationId(materialId))
                    outcome.toWorkerResult(runAttemptCount)
                }
            }

        private suspend fun setForegroundSafely(
            materialId: String,
            progress: NotificationProgress,
        ) {
            runAfterForegroundPromotion(
                promoteToForeground = { setForeground(foregroundInfo(materialId, progress)) },
                onPromotionUnavailable = { failure ->
                    logger.warning(TAG, "Upload foreground progress is unavailable; continuing transfer", failure)
                },
                work = {},
            )
        }

        // FOREGROUND_SERVICE_TYPE_DATA_SYNC was added in API 29; ForegroundInfo's 3-arg
        // constructor accepts the type on every supported API level and WorkManager itself only
        // applies it where the platform understands it, so this is safe below API 29.
        @SuppressLint("InlinedApi")
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

        private fun finishedNotification(
            title: String,
            message: String,
        ): Notification =
            notificationFactory.progress(
                title = title,
                text = message,
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

internal fun UploadOutcome.toWorkerResult(runAttemptCount: Int): ListenableWorker.Result =
    when (this) {
        UploadOutcome.Synced -> ListenableWorker.Result.success()
        is UploadOutcome.Retryable -> retryWhileAttemptsRemain(runAttemptCount)
        is UploadOutcome.Permanent, UploadOutcome.MaterialMissing -> ListenableWorker.Result.failure()
    }
