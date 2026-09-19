package dev.studyflow.core.scheduling

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.domain.sync.SyncEngine
import dev.studyflow.core.domain.sync.SyncOutcome
import dev.studyflow.core.domain.sync.SyncTrigger

/** The trigger a sync work request carries in its input data, as a [SyncTrigger] name. */
public const val EXTRA_SYNC_TRIGGER: String = "dev.studyflow.core.scheduling.SYNC_TRIGGER"

/**
 * Runs one sync pass under WorkManager's constraints and backoff (issue #55).
 *
 * Deliberately thin: all of the ordering, batching and resumption rules live in [SyncEngine], which
 * is testable without Android. What belongs here is the platform translation — reading why the run
 * was asked for, and turning the outcome into the [Result] WorkManager acts on.
 *
 * A retryable failure returns [Result.retry] so the exponential backoff configured by
 * [WorkManagerSyncCoordinator] applies; a permanent one returns [Result.failure], because retrying
 * a request the server will keep rejecting only costs the user battery. Neither loses data: the
 * queue is only acknowledged on acceptance, so whatever did not go out is still there for the next
 * run.
 */
@HiltWorker
public class SyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted parameters: WorkerParameters,
        private val syncEngine: SyncEngine,
        private val logger: AppLogger,
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            val trigger =
                inputData.getString(EXTRA_SYNC_TRIGGER)?.let { name ->
                    runCatching { SyncTrigger.valueOf(name) }.getOrNull()
                } ?: SyncTrigger.SCHEDULED

            return when (val outcome = syncEngine.sync(trigger)) {
                SyncOutcome.Idle -> {
                    Result.success()
                }

                is SyncOutcome.Synced -> {
                    Result.success()
                }

                is SyncOutcome.Failed -> {
                    logger.warning(TAG, "Sync failed: ${outcome.failure.message}")
                    if (outcome.failure.retryable) Result.retry() else Result.failure()
                }
            }
        }

        private companion object {
            const val TAG = "SyncWorker"
        }
    }
