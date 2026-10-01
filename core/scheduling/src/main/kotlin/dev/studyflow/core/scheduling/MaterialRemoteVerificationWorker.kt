package dev.studyflow.core.scheduling

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs [MaterialRemoteVerifier] in the background. A `stat` is a few hundred bytes, so this needs
 * any network rather than Wi-Fi; healed materials re-upload under the user's own upload constraint.
 */
@HiltWorker
public class MaterialRemoteVerificationWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted parameters: WorkerParameters,
        private val verifier: MaterialRemoteVerifier,
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            verifier.verify()
            return Result.success()
        }
    }

/**
 * Enqueues one [MaterialRemoteVerificationWorker] per cold start. `KEEP` so an app reopened while a
 * check is still waiting for a network does not stack a second one; the ledger keeps each run to the
 * materials not yet confirmed, so a repeat is cheap anyway.
 */
public class MaterialRemoteVerificationScheduler(
    private val workManager: WorkManager,
) {
    public constructor(context: Context) : this(WorkManager.getInstance(context))

    public fun ensureScheduled() {
        val request =
            OneTimeWorkRequestBuilder<MaterialRemoteVerificationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val WORK_NAME = "studyflow.material-remote-verification"
    }
}
