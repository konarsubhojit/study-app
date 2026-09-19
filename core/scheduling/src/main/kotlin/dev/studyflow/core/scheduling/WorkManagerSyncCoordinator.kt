package dev.studyflow.core.scheduling

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import dev.studyflow.core.domain.session.SessionCommandObserver
import dev.studyflow.core.domain.session.SessionCommandResult
import dev.studyflow.core.domain.sync.SyncScheduler
import dev.studyflow.core.domain.sync.SyncTrigger
import dev.studyflow.core.model.SessionStatus
import java.util.concurrent.TimeUnit

/** The unique `WorkManager` name for sync — one drain at a time, whoever asked for it. */
public const val SYNC_WORK_NAME: String = "sync:sessions"

/** The unique name of the recurring catch-up sync. */
public const val PERIODIC_SYNC_WORK_NAME: String = "sync:sessions:periodic"

/**
 * Turns "please sync" into `WorkManager` work (issue #55).
 *
 * Two platform guarantees matter here and are the reason WorkManager is used at all:
 *
 * - **A connected network is a constraint, not a check.** The work simply does not run offline,
 *   and starts itself when connectivity returns — which is what makes the outbound queue drain
 *   without the app being opened.
 * - **Failures back off exponentially.** A server that is down, or a device on a captive portal,
 *   is retried at widening intervals rather than in a tight loop that would flatten the battery.
 *
 * The work is unique, so a mutation burst (stopping three sessions in a row) enqueues one drain
 * rather than three, and a manual "Sync now" replaces whatever was waiting because the user is
 * asking for *now* rather than eventually.
 */
public class WorkManagerSyncCoordinator(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context),
) : SyncScheduler {
    override suspend fun requestSync(trigger: SyncTrigger) {
        enqueue(trigger)
    }

    /**
     * Enqueues the recurring catch-up run, once.
     *
     * Without it a device that never records a session would never *receive* one either: every
     * other trigger is a local mutation or a button press, and neither happens on the tablet that
     * only reads. `KEEP` makes this safe to call on every cold start — re-enqueueing would reset
     * the cadence's anchor each time the app is opened.
     */
    public fun ensureScheduled() {
        val request =
            PeriodicWorkRequestBuilder<SyncWorker>(SYNC_INTERVAL_HOURS, TimeUnit.HOURS)
                .setInputData(workDataOf(EXTRA_SYNC_TRIGGER to SyncTrigger.SCHEDULED.name))
                .setConstraints(connectedNetwork())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * The non-suspending form, for callers that are not in a coroutine — notably the post-commit
     * session observer, which must not block the write it is reacting to.
     */
    public fun enqueue(trigger: SyncTrigger) {
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(EXTRA_SYNC_TRIGGER to trigger.name))
                .setConstraints(connectedNetwork())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
        workManager.enqueueUniqueWork(SYNC_WORK_NAME, trigger.existingWorkPolicy(), request)
    }

    private fun connectedNetwork(): Constraints =
        Constraints
            .Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    /**
     * `REPLACE` for a manual run, `KEEP` otherwise.
     *
     * A user who taps "Sync now" while a scheduled drain is waiting on its backoff expects that
     * tap to start a run, not to be swallowed by the pending one. Automatic triggers take the
     * opposite view: a run is already coming, and enqueueing another would only restart its
     * backoff.
     */
    private fun SyncTrigger.existingWorkPolicy(): ExistingWorkPolicy =
        when (this) {
            SyncTrigger.MANUAL -> ExistingWorkPolicy.REPLACE
            SyncTrigger.SCHEDULED, SyncTrigger.OUTBOUND -> ExistingWorkPolicy.KEEP
        }

    private companion object {
        /**
         * Often enough that a second device is never a day behind, rarely enough that a phone
         * which never opens the app does not pay for it; WorkManager batches it with whatever else
         * is waiting anyway.
         */
        const val SYNC_INTERVAL_HOURS = 6L
    }
}

/**
 * Asks for a drain once a session command has been committed.
 *
 * Runs *after* the transaction that wrote both the session and its queue entry, so the work it
 * enqueues can never observe a half-written change. Only a stopped session is worth a request: a
 * running or paused one is never queued, so a drain triggered by it would find nothing to send —
 * and [SyncTrigger.OUTBOUND] means the engine makes no request at all in that case anyway.
 */
public class SyncOnSessionCommandObserver(
    private val coordinator: WorkManagerSyncCoordinator,
) : SessionCommandObserver {
    override fun onSessionCommandApplied(result: SessionCommandResult.Applied) {
        if (result.session.status != SessionStatus.STOPPED) return
        coordinator.enqueue(SyncTrigger.OUTBOUND)
    }
}
