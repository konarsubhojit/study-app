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
import android.provider.Settings
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
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ReminderPlatformScheduler {
    override fun scheduleInexact(plan: ReminderPlan) {
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
    }

    override fun scheduleExact(plan: ReminderPlan) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            plan.triggerAt.toEpochMilliseconds(),
            operation(plan.reminderId),
        )
    }

    override fun scheduleAlarmClock(plan: ReminderPlan) {
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(
                plan.triggerAt.toEpochMilliseconds(),
                showIntent(plan.reminderId),
            ),
            operation(plan.reminderId),
        )
    }

    override fun cancel(reminderId: String) {
        alarmManager.cancel(operation(reminderId))
        workManager.cancelUniqueWork(workName(reminderId))
    }

    private fun delayMillis(plan: ReminderPlan): Long =
        max(0L, plan.triggerAt.toEpochMilliseconds() - nowMillis())

    private fun operation(reminderId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            reminderIntent(reminderId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun showIntent(reminderId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, reminderId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun reminderIntent(reminderId: String): Intent =
        Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_DELIVER_REMINDER
            data = reminderUri(reminderId)
            putExtra(EXTRA_REMINDER_ID, reminderId)
        }

    private fun reminderUri(reminderId: String): Uri =
        Uri.Builder()
            .scheme("studyflow")
            .authority("reminders")
            .appendPath(reminderId)
            .build()

    private companion object {
        private const val ACTION_DELIVER_REMINDER = "dev.studyflow.core.scheduling.DELIVER_REMINDER"
    }
}

public class ReminderDeliveryWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result = Result.success()
}

public class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent): Unit = Unit
}

public const val EXTRA_REMINDER_ID: String = "dev.studyflow.core.scheduling.REMINDER_ID"

public fun exactAlarmSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }

private fun workData(plan: ReminderPlan): Data =
    Data.Builder()
        .putString(EXTRA_REMINDER_ID, plan.reminderId)
        .putString("task_id", plan.taskId)
        .putLong("trigger_at_epoch_millis", plan.triggerAt.toEpochMilliseconds())
        .putLong("occurrence_at_epoch_millis", plan.occurrenceAt.toEpochMilliseconds())
        .build()

private fun workName(reminderId: String): String = "reminder:$reminderId"

private fun workTag(reminderId: String): String = "reminder-id:$reminderId"
