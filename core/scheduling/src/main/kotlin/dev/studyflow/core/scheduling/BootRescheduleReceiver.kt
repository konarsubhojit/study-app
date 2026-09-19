package dev.studyflow.core.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import dev.studyflow.core.scheduling.di.SchedulingEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-arms every reminder after a reboot, an app update, or (defensively) the device's clock
 * changing — all of which drop `AlarmManager` registrations without telling the app (issue #48,
 * docs/adr/0004's "re-arm aggressively").
 *
 * `ACTION_MY_PACKAGE_REPLACED` covers an app update the same way `ACTION_BOOT_COMPLETED` covers a
 * reboot; clock and timezone changes are handled here too because the next wall-clock occurrence
 * may have moved. Without this receiver, an
 * `ALARM_CLOCK`-precision reminder set for tomorrow would never fire if the device rebooted
 * tonight and the app was never opened again before then — exactly the "locked, screen off, app
 * never opened since reboot" scenario the acceptance criteria call out.
 *
 * The weekly summary is re-armed here too (issue #63). Its `WorkManager` request already survives
 * a reboot and an app update on its own, but a timezone or clock change moves the wall-clock time
 * the user chose without moving the queued work, so it is recomputed alongside the reminders.
 */
public class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in ACTIONS) {
            return
        }

        val entryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, SchedulingEntryPoint::class.java)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + entryPoint.dispatcherProvider().default).launch {
            try {
                entryPoint.reminderIntegrityCoordinator().reconcile(entryPoint.taskRepository().observeTasks().first())
                entryPoint.weeklySummaryScheduling().sync()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val ACTIONS =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
            )
    }
}
