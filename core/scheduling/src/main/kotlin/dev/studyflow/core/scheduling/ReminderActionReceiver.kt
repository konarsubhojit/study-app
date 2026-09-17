package dev.studyflow.core.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.EntryPointAccessors
import dev.studyflow.core.scheduling.di.SchedulingEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles a tap on a reminder notification's Complete, Snooze or Start-session action.
 *
 * Registered with no intent filter: every `PendingIntent` that targets it is explicit (see
 * [ReminderDeliveryCoordinator]), so there is nothing here for another app to trigger. [goAsync]
 * plus a short-lived scope is what lets [ReminderActionExecutor]'s suspend repository writes
 * finish even when Android considers the receiver's own lifetime over — the same reasoning as
 * [ReminderAlarmReceiver].
 */
public class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val kind = ReminderActionKind.entries.firstOrNull { it.intentAction == intent.action }
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID)
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        if (kind == null || reminderId == null || taskId == null) {
            Log.w(TAG, "Reminder action broadcast is missing its action, reminder or task id; dropping it")
            return
        }

        val entryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, SchedulingEntryPoint::class.java)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + entryPoint.dispatcherProvider().default).launch {
            try {
                entryPoint.reminderActionExecutor().execute(kind, reminderId, taskId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ReminderAction"
    }
}
