package dev.studyflow.core.scheduling

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.studyflow.core.domain.reminder.ReminderPlan
import dev.studyflow.core.domain.reminder.SchedulingCapabilities
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.time.Clock

public class AndroidSchedulingCapabilitiesProvider(
    private val context: Context,
    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java),
    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java),
) : SchedulingCapabilitiesProvider {
    override fun currentCapabilities(): SchedulingCapabilities =
        SchedulingCapabilities(
            canScheduleExactAlarms = canScheduleExactAlarms(),
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            canUseAlarmClock = true,
            canUseFullScreenIntent = canUseFullScreenIntent(),
            batteryOptimised = isBatteryOptimised(),
        )

    private fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun canUseFullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            notificationManager.canUseFullScreenIntent()

    private fun isBatteryOptimised(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return !powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}

public class AndroidReminderPlatformScheduler(
    private val context: Context,
    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java),
    private val workManager: WorkManager = WorkManager.getInstance(context),
    private val showIntentFactory: () -> Intent = {
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
    },
    private val onExactAlarmDenied: (SecurityException) -> Unit = {},
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ReminderPlatformScheduler {
    override fun scheduleInexact(plan: ReminderPlan): PlatformScheduleOutcome {
        val request =
            OneTimeWorkRequestBuilder<ReminderDeliveryWorker>()
                .setInputData(workData(plan))
                .setInitialDelay(delayMillis(plan), TimeUnit.MILLISECONDS)
                .addTag(workTag(plan.reminderId))
                .build()

        workManager.enqueueUniqueWork(
            workName(plan.reminderId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return PlatformScheduleOutcome.SCHEDULED
    }

    override fun scheduleExact(plan: ReminderPlan): PlatformScheduleOutcome =
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                plan.triggerAt.toEpochMilliseconds(),
                operation(plan.reminderId),
            )
            PlatformScheduleOutcome.SCHEDULED
        } catch (exception: SecurityException) {
            onExactAlarmDenied(exception)
            alarmManager.cancel(operation(plan.reminderId))
            scheduleInexact(plan)
            PlatformScheduleOutcome.EXACT_ALARM_DENIED_FALLBACK_TO_INEXACT
        }

    override fun scheduleAlarmClock(plan: ReminderPlan): PlatformScheduleOutcome {
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(
                plan.triggerAt.toEpochMilliseconds(),
                showIntent(plan.taskId),
            ),
            operation(plan.reminderId),
        )
        return PlatformScheduleOutcome.SCHEDULED
    }

    override fun cancel(reminderId: String) {
        alarmManager.cancel(operation(reminderId))
        workManager.cancelUniqueWork(workName(reminderId))
    }

    private fun delayMillis(plan: ReminderPlan): Long = max(0L, plan.triggerAt.toEpochMilliseconds() - nowMillis())

    private fun operation(reminderId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            PendingIntentSlot.DELIVERY.requestCode,
            reminderIntent(reminderId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun showIntent(taskId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            PendingIntentSlot.SHOW.requestCode,
            showIntentFactory().withTaskId(taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun reminderIntent(reminderId: String): Intent =
        Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_DELIVER_REMINDER
            data = reminderUri(reminderId)
            putExtra(EXTRA_REMINDER_ID, reminderId)
        }

    private companion object {
        private const val ACTION_DELIVER_REMINDER = "dev.studyflow.core.scheduling.DELIVER_REMINDER"
    }
}

/**
 * Placeholder worker for WorkManager-triggered reminders.
 *
 * Notification rendering is intentionally left to the notification feature; this worker is the
 * stable scheduling hand-off point and carries the reminder metadata that delivery will consume.
 */
public class ReminderDeliveryWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result {
        Log.w(TAG, "TODO: deliver WorkManager reminder notification")
        return Result.success()
    }
}

/**
 * Placeholder receiver for AlarmManager-triggered reminders.
 *
 * Notification rendering is intentionally left to the notification feature; this receiver is the
 * stable scheduling hand-off point and receives [EXTRA_REMINDER_ID].
 */
public class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: "missing-reminder-id"
        Log.w(TAG, "TODO: deliver AlarmManager reminder notification for $reminderId")
    }
}

public const val EXTRA_REMINDER_ID: String = "dev.studyflow.core.scheduling.REMINDER_ID"
public const val EXTRA_TASK_ID: String = "dev.studyflow.core.scheduling.TASK_ID"
public const val EXTRA_TRIGGER_AT_EPOCH_MILLIS: String =
    "dev.studyflow.core.scheduling.TRIGGER_AT_EPOCH_MILLIS"
public const val EXTRA_OCCURRENCE_AT_EPOCH_MILLIS: String =
    "dev.studyflow.core.scheduling.OCCURRENCE_AT_EPOCH_MILLIS"

public fun exactAlarmSettingsIntent(context: Context): Intent =
    Intent(ExactAlarmPermissionRationale.REQUEST_EXACT_ALARM_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }

private fun workData(plan: ReminderPlan): Data =
    Data
        .Builder()
        .putString(EXTRA_REMINDER_ID, plan.reminderId)
        .putString(EXTRA_TASK_ID, plan.taskId)
        .putLong(EXTRA_TRIGGER_AT_EPOCH_MILLIS, plan.triggerAt.toEpochMilliseconds())
        .putLong(EXTRA_OCCURRENCE_AT_EPOCH_MILLIS, plan.occurrenceAt.toEpochMilliseconds())
        .build()

private fun workName(reminderId: String): String = "reminder:$reminderId"

private fun workTag(reminderId: String): String = "reminder-id:$reminderId"

private fun Intent.withTaskId(taskId: String): Intent =
    apply {
        data = taskUri(taskId)
        putExtra(EXTRA_TASK_ID, taskId)
    }

private fun taskUri(taskId: String): Uri =
    Uri
        .Builder()
        .scheme("studyflow")
        .authority("tasks")
        .appendPath(taskId)
        .build()

private fun reminderUri(reminderId: String): Uri =
    Uri
        .Builder()
        .scheme("studyflow")
        .authority("reminders")
        .appendPath(reminderId)
        .build()

private enum class PendingIntentSlot {
    DELIVERY,
    SHOW,
    ;

    // Reminder identity lives in each intent's data URI; these codes only separate intent roles.
    val requestCode: Int
        get() = ordinal
}

private const val TAG: String = "ReminderScheduling"
