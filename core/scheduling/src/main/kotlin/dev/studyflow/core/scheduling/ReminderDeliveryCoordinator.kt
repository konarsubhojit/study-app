package dev.studyflow.core.scheduling

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.SystemWallClock
import dev.studyflow.core.domain.reminder.SchedulingCapabilities
import dev.studyflow.core.domain.subjects.SubjectRepository
import dev.studyflow.core.domain.tasks.TaskRepository
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.ReminderMode
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subject
import dev.studyflow.core.notifications.AlertPresentation
import dev.studyflow.core.notifications.NotificationAction
import dev.studyflow.core.notifications.StudyFlowNotificationChannel
import dev.studyflow.core.notifications.StudyFlowNotificationFactory
import dev.studyflow.core.notifications.StudyFlowNotifier
import dev.studyflow.core.notifications.StudyFlowPendingIntents
import kotlinx.coroutines.flow.first

/** The three things a reminder notification lets the user do without opening the app. */
public enum class ReminderActionKind(
    public val intentAction: String,
) {
    COMPLETE("dev.studyflow.core.scheduling.action.COMPLETE"),
    SNOOZE("dev.studyflow.core.scheduling.action.SNOOZE"),
    START_SESSION("dev.studyflow.core.scheduling.action.START_SESSION"),

    /**
     * Silences an alarm-style reminder without completing the task or rescheduling it (issue #48).
     *
     * Distinct from [COMPLETE]: dismissing an alarm says "I heard it", not "I did it" — the task
     * stays open. Used by the full-screen alarm UI's Dismiss button, and as the notification's
     * delete action so swiping the alarm notification away in the shade stops the ringing too.
     */
    DISMISS("dev.studyflow.core.scheduling.action.DISMISS"),
}

/** The shared group every reminder notification and its summary carry (issue #47). */
public const val REMINDER_GROUP_KEY: String = "studyflow.reminders"

/** A stable id derived from the reminder id, so re-delivery updates the same notification. */
public fun reminderNotificationId(reminderId: String): Int = "reminder:$reminderId".hashCode()

/** The one summary notification every group of reminders collapses into. */
internal val GROUP_SUMMARY_NOTIFICATION_ID = "$REMINDER_GROUP_KEY.summary".hashCode()

/**
 * Turns a fired reminder into what the user actually sees (issue #47).
 *
 * Both hand-off points — [ReminderDeliveryWorker] and [ReminderAlarmReceiver] — resolve to a
 * reminder id and a task id and call [deliver]; everything about *what* gets shown lives here
 * exactly once, so the two platform primitives cannot drift apart in behaviour.
 *
 * A task that is missing, completed or deleted is a reminder that outlived its task — the
 * schedule replaces a task's registrations on every save, but an in-flight alarm from before that
 * replacement can still land — so [deliver] cancels rather than posts in that case.
 */
public class ReminderDeliveryCoordinator(
    private val context: Context,
    private val taskRepository: TaskRepository,
    private val subjectRepository: SubjectRepository,
    private val notifier: StudyFlowNotifier,
    private val notificationFactory: StudyFlowNotificationFactory,
    /** True when the digest is on, in which case individual reminders stay silent (issue #47). */
    private val digestEnabled: suspend () -> Boolean = { false },
    /**
     * Whether a full-screen intent can currently be shown (issue #48); re-read on every delivery
     * because Android 14 can revoke `USE_FULL_SCREEN_INTENT` at any time. When it cannot, an
     * alarm-style reminder still posts to [StudyFlowNotificationChannel.ALARMS] — high importance,
     * heads-up — it just cannot take over the screen, which is the degradation
     * `ReminderDegradation.FULL_SCREEN_INTENT_DENIED` already names.
     */
    private val capabilitiesProvider: SchedulingCapabilitiesProvider =
        SchedulingCapabilitiesProvider {
            SchedulingCapabilities()
        },
    private val notificationManager: NotificationManagerCompat = NotificationManagerCompat.from(context),
    private val clock: Clock = SystemWallClock,
) {
    public suspend fun deliver(
        reminderId: String,
        taskId: String,
    ) {
        val notificationId = reminderNotificationId(reminderId)
        val task = taskRepository.observeTask(taskId).first()
        val reminder = task?.reminders?.firstOrNull { it.id == reminderId }
        if (task == null || reminder == null) {
            notifier.cancel(notificationId)
            return
        }
        // The digest already told the user about every task due today; a second, per-task ping
        // for the same tasks would be exactly the spam this issue asks the app not to produce.
        // An alarm-style reminder is exempt: "wake me for the exam" is not the kind of nudge the
        // digest was built to replace, and silencing it would defeat the reminder's whole point.
        if (task.deleted || task.isCompleted || shouldSilenceForDigest(reminder, digestEnabled)) {
            notifier.cancel(notificationId)
            return
        }

        val subject = task.subjectId?.let { subjectRepository.subject(it) }
        val channel =
            if (reminder.mode == ReminderMode.ALARM) {
                StudyFlowNotificationChannel.ALARMS
            } else {
                StudyFlowNotificationChannel.TASK_REMINDERS
            }
        val result =
            notifier.post(
                notificationId,
                channel,
                notification(task, reminder, subject),
            )
        if (result.posted) {
            taskRepository.updateReminder(reminder.copy(lastFiredAt = clock.now(), snooze = null))
            refreshGroupSummary()
            if (reminder.mode == ReminderMode.ALARM) {
                AlarmPlaybackService.start(context, reminderId, taskId, notificationId)
            }
        }
    }

    private fun notification(
        task: StudyTask,
        reminder: Reminder,
        subject: Subject?,
    ): android.app.Notification =
        if (reminder.mode == ReminderMode.ALARM) {
            alarmNotification(task, reminder, subject)
        } else {
            reminderNotification(task, reminder, subject)
        }

    private fun reminderNotification(
        task: StudyTask,
        reminder: Reminder,
        subject: Subject?,
    ): android.app.Notification {
        val contentIntent =
            StudyFlowPendingIntents.activity(
                context,
                reminderNotificationId(reminder.id),
                taskUri(task.id),
            )
        val publicVersion =
            notificationFactory.alert(
                channel = StudyFlowNotificationChannel.TASK_REMINDERS,
                title = PUBLIC_TITLE,
                text = "",
                contentIntent = contentIntent,
            )
        return notificationFactory.alert(
            channel = StudyFlowNotificationChannel.TASK_REMINDERS,
            title = task.title,
            text = dueText(task),
            contentIntent = contentIntent,
            actions =
                listOf(
                    action(ReminderActionKind.COMPLETE, "Complete", task.id, reminder.id),
                    action(ReminderActionKind.SNOOZE, "Snooze 10m", task.id, reminder.id),
                    action(ReminderActionKind.START_SESSION, "Start session", task.id, reminder.id),
                ),
            presentation =
                AlertPresentation(
                    color = subject?.colorArgb,
                    publicVersion = publicVersion,
                    group = REMINDER_GROUP_KEY,
                ),
        )
    }

    /**
     * The alarm-style variant (issue #48): [StudyFlowNotificationChannel.ALARMS], a
     * `fullScreenIntent` at [AlarmActivity] when the platform currently allows one, and only
     * Dismiss/Snooze — Complete and Start-session stay on the ordinary reminder, since an alarm
     * that just went off is answered by silencing it, not by picking a different task action.
     */
    private fun alarmNotification(
        task: StudyTask,
        reminder: Reminder,
        subject: Subject?,
    ): android.app.Notification {
        val notificationId = reminderNotificationId(reminder.id)
        val contentIntent =
            StudyFlowPendingIntents.explicitActivity(
                context,
                notificationId,
                AlarmActivity.intent(context, reminder.id, task.id),
            )
        val publicVersion =
            notificationFactory.alert(
                channel = StudyFlowNotificationChannel.ALARMS,
                title = PUBLIC_TITLE,
                text = "",
                contentIntent = contentIntent,
            )
        val fullScreenIntent =
            if (capabilitiesProvider.currentCapabilities().canUseFullScreenIntent) contentIntent else null
        return notificationFactory.alert(
            channel = StudyFlowNotificationChannel.ALARMS,
            title = task.title,
            text = dueText(task),
            contentIntent = contentIntent,
            fullScreenIntent = fullScreenIntent,
            actions =
                listOf(
                    action(ReminderActionKind.DISMISS, "Dismiss", task.id, reminder.id),
                    action(ReminderActionKind.SNOOZE, "Snooze 10m", task.id, reminder.id),
                ),
            presentation =
                AlertPresentation(
                    color = subject?.colorArgb,
                    publicVersion = publicVersion,
                    group = REMINDER_GROUP_KEY,
                ),
        )
    }

    private fun action(
        kind: ReminderActionKind,
        title: String,
        taskId: String,
        reminderId: String,
    ): NotificationAction =
        NotificationAction(
            title = title,
            icon = kind.icon,
            intent =
                StudyFlowPendingIntents.broadcast(
                    context,
                    requestCode = 0,
                    intent = actionIntent(kind, taskId, reminderId),
                ),
        )

    private fun actionIntent(
        kind: ReminderActionKind,
        taskId: String,
        reminderId: String,
    ): Intent =
        Intent(context, ReminderActionReceiver::class.java).apply {
            action = kind.intentAction
            data = reminderActionUri(kind, reminderId)
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_TASK_ID, taskId)
        }

    /**
     * Collapses every currently-active reminder into one summary once there are two or more, so
     * ten due tasks show as one grouped, readable notification rather than ten heads-up alerts.
     *
     * Also called by [ReminderActionExecutor] after Complete/Snooze/Start-session dismisses one
     * reminder, so the summary's count and list never lag behind an action taken from the shade.
     * Matched by group key rather than channel id alone, so the unrelated single digest
     * notification (same channel, its own id, no group) is never miscounted as one of these.
     */
    public fun refreshGroupSummary() {
        val active =
            notificationManager.activeNotifications.filter {
                it.notification.group == REMINDER_GROUP_KEY && it.id != GROUP_SUMMARY_NOTIFICATION_ID
            }
        if (active.size < MIN_REMINDERS_TO_GROUP) {
            notifier.cancel(GROUP_SUMMARY_NOTIFICATION_ID)
            return
        }

        val titles =
            active.mapNotNull {
                it.notification.extras
                    .getCharSequence(
                        NotificationCompat.EXTRA_TITLE,
                    )?.toString()
            }
        val summaryContentIntent =
            StudyFlowPendingIntents.activity(
                context,
                GROUP_SUMMARY_NOTIFICATION_ID,
                tasksListUri(),
            )
        val publicVersion =
            notificationFactory.alert(
                channel = StudyFlowNotificationChannel.TASK_REMINDERS,
                title = PUBLIC_TITLE,
                text = "",
                contentIntent = summaryContentIntent,
            )
        val summary =
            notificationFactory.groupedReminderSummary(
                title = "${active.size} tasks need your attention",
                text = "${active.size} reminders due",
                lines = titles,
                contentIntent = summaryContentIntent,
                group = REMINDER_GROUP_KEY,
                publicVersion = publicVersion,
            )
        notifier.post(GROUP_SUMMARY_NOTIFICATION_ID, StudyFlowNotificationChannel.TASK_REMINDERS, summary)
    }

    private companion object {
        const val PUBLIC_TITLE = "You have a reminder"
        const val MIN_REMINDERS_TO_GROUP = 2
    }
}

/** A small, stable system icon per action; none of these modules ship their own drawables. */
private val ReminderActionKind.icon: Int
    get() =
        when (this) {
            ReminderActionKind.COMPLETE -> android.R.drawable.checkbox_on_background
            ReminderActionKind.SNOOZE -> android.R.drawable.ic_lock_idle_alarm
            ReminderActionKind.START_SESSION -> android.R.drawable.ic_media_play
            ReminderActionKind.DISMISS -> android.R.drawable.ic_menu_close_clear_cancel
        }

/** Distinguishes the three actions' `PendingIntent`s from each other and from the content intent. */
private fun reminderActionUri(
    kind: ReminderActionKind,
    reminderId: String,
): Uri =
    Uri
        .Builder()
        .scheme("studyflow-internal")
        .authority("reminder-action")
        .appendPath(kind.name)
        .appendPath(reminderId)
        .build()

/** The whole task list, used by the group summary's content intent. */
private fun tasksListUri(): Uri =
    Uri
        .Builder()
        .scheme("studyflow")
        .authority("tasks")
        .build()

/** 24-hour local time; `task.timeZone` is what [StudyTask.dueAt] is already expressed in. */
private fun dueText(task: StudyTask): String {
    val dueAt = task.dueAt ?: return "Due now"
    return "Due %02d:%02d".format(dueAt.hour, dueAt.minute)
}

/**
 * True when [reminder] should be folded into the daily digest instead of posted individually.
 * Alarm-style reminders are always exempt — see the rationale at the [ReminderDeliveryCoordinator.deliver]
 * call site.
 */
private suspend fun shouldSilenceForDigest(
    reminder: Reminder,
    digestEnabled: suspend () -> Boolean,
): Boolean = reminder.mode == ReminderMode.NOTIFICATION && digestEnabled()
