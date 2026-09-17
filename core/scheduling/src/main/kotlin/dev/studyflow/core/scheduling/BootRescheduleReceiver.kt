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
 * reboot; both silently discard every alarm StudyFlow had scheduled. Without this receiver, an
 * `ALARM_CLOCK`-precision reminder set for tomorrow would never fire if the device rebooted
 * tonight and the app was never opened again before then — exactly the "locked, screen off, app
 * never opened since reboot" scenario the acceptance criteria call out.
 */
public class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val entryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, SchedulingEntryPoint::class.java)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + entryPoint.dispatcherProvider().default).launch {
            try {
                val tasks = entryPoint.taskRepository().observeTasks().first()
                entryPoint.reminderSchedulingService().rescheduleAll(tasks)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
