package dev.studyflow.core.scheduling

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.datastore.WeeklySummarySettings
import dev.studyflow.core.domain.stats.WeeklyDelivery
import dev.studyflow.core.domain.stats.WeeklySummaryScheduling
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** The unique `WorkManager` name of the weekly summary — one enqueued digest, ever. */
internal const val WEEKLY_SUMMARY_WORK_NAME = "studyflow.weekly-summary"

/** How long one cadence of [WeeklySummaryWorker] is. */
private const val WEEKLY_SUMMARY_INTERVAL_DAYS = 7L

/**
 * Arms — or disarms — the weekly summary (issue #63).
 *
 * Periodic work anchored with an initial delay to the user's next chosen day and time, rather than
 * the digest's "every 24 hours and let the worker decide": a recap has a *window* the user picked,
 * and a plain interval would land it at whatever time of day the app happened to be installed.
 * WorkManager persists the request, so the cadence survives a reboot and an app update on its own;
 * [BootRescheduleReceiver] calls [sync] anyway because a timezone or clock change moves the
 * wall-clock target even though the queued work did not move with it.
 *
 * `CANCEL_AND_REENQUEUE` rather than `KEEP`/`UPDATE`: every call recomputes the next occurrence
 * from the current settings, so re-anchoring is exactly what is wanted — a user who moves the
 * digest from Sunday to Wednesday must not wait out the old anchor first. Opting out cancels the
 * work outright, which is what makes "opting out stops it immediately and permanently" true
 * without relying on the worker to keep no-opping forever.
 */
public class WeeklySummaryScheduler(
    private val settings: WeeklySummarySettings,
    private val workManager: WorkManager,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
) : WeeklySummaryScheduling {
    override suspend fun sync() {
        val schedule = settings.schedule.first()
        if (!schedule.enabled) {
            workManager.cancelUniqueWork(WEEKLY_SUMMARY_WORK_NAME)
            return
        }

        val now = clock.now()
        val next =
            WeeklyDelivery.nextOccurrence(
                now = now,
                zone = timeZoneProvider.current(),
                isoDayOfWeek = schedule.isoDayOfWeek,
                hour = schedule.hour,
                minute = schedule.minute,
            )
        val request =
            PeriodicWorkRequestBuilder<WeeklySummaryWorker>(WEEKLY_SUMMARY_INTERVAL_DAYS, TimeUnit.DAYS)
                .setInitialDelay((next - now).inWholeMinutes, TimeUnit.MINUTES)
                .build()
        workManager.enqueueUniquePeriodicWork(
            WEEKLY_SUMMARY_WORK_NAME,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request,
        )
    }
}
