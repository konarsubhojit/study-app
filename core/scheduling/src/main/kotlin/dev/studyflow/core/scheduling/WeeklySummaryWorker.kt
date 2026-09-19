package dev.studyflow.core.scheduling

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs [WeeklySummaryDelivery] on the user's chosen day and time (issue #63).
 *
 * Deliberately thin: every rule about whether a recap is due, and what it says, lives in
 * [WeeklySummaryDelivery] so it can be tested without WorkManager. Always [Result.success] —
 * "nothing to say this week" and "the user opted out" are outcomes, not failures, and retrying
 * either of them would be pointless.
 */
@HiltWorker
public class WeeklySummaryWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted parameters: WorkerParameters,
        private val delivery: WeeklySummaryDelivery,
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            delivery.deliverIfDue()
            return Result.success()
        }
    }
