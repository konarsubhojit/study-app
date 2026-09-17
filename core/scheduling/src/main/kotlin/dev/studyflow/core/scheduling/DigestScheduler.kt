package dev.studyflow.core.scheduling

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues [DigestNotificationWorker] once, on app start, so it keeps running daily thereafter.
 *
 * `KEEP` rather than `REPLACE`/`UPDATE`: this call happens on every process start, and re-enqueuing
 * would reset the daily cadence's anchor every time the app is opened, which drifts the delivery
 * time rather than holding it steady. The worker itself decides whether to post anything — this
 * class only guarantees it runs.
 *
 * The interval is a plain 24 hours rather than an exact alignment to the user's configured
 * `reminder_hour`/`reminder_minute`: WorkManager periodic work already cannot promise a precise
 * time of day, and [DigestNotificationWorker] reads the configured time itself on every run, so a
 * closer alignment would need an exact alarm for a morning digest — disproportionate for what this
 * issue asks for.
 */
public class DigestScheduler(
    private val workManager: WorkManager,
) {
    public constructor(context: Context) : this(WorkManager.getInstance(context))

    public fun ensureScheduled() {
        val request =
            PeriodicWorkRequestBuilder<DigestNotificationWorker>(
                DIGEST_INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val WORK_NAME = "studyflow.digest"
        const val DIGEST_INTERVAL_HOURS = 24L
    }
}
