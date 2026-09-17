package dev.studyflow.core.scheduling

import android.content.Context
import android.provider.Settings
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.ElapsedRealtimeSource
import dev.studyflow.core.common.time.SystemWallClock
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.tasks.TaskRepository
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.SnoozeState
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.TimeAnchor
import dev.studyflow.core.notifications.StudyFlowNotifier
import kotlinx.coroutines.flow.first
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * How long a single tap of the notification's Snooze action postpones a reminder.
 *
 * Fixed rather than configurable: the notification only has room for one button, and ten minutes
 * is long enough to finish "just a moment" without the reminder going quiet for the rest of the
 * day. [Reminder.snooze]'s `count` still tracks repeat snoozes for a future "stop asking" nudge.
 */
public val SNOOZE_DURATION: kotlin.time.Duration = 10.minutes

/**
 * Executes the action a user picked from a reminder notification, entirely in the background.
 *
 * Called from [ReminderActionReceiver], which must survive the app process being dead — every
 * write here is a single suspend repository call, never a chain that depends on in-memory state
 * from a previous notification delivery.
 */
public class ReminderActionExecutor(
    private val context: Context,
    private val taskRepository: TaskRepository,
    private val schedulingService: ReminderSchedulingService,
    private val sessionRepository: SessionRepository,
    private val notifier: StudyFlowNotifier,
    private val wallClock: Clock = SystemWallClock,
    private val elapsedRealtimeSource: ElapsedRealtimeSource = AndroidElapsedRealtimeSource,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    public suspend fun execute(
        action: ReminderActionKind,
        reminderId: String,
        taskId: String,
    ) {
        val notificationId = reminderNotificationId(reminderId)
        val task = taskRepository.observeTask(taskId).first()
        if (task == null) {
            notifier.cancel(notificationId)
            return
        }

        when (action) {
            ReminderActionKind.COMPLETE -> complete(task, notificationId)
            ReminderActionKind.SNOOZE -> snooze(task, reminderId, notificationId)
            ReminderActionKind.START_SESSION -> startSession(task, notificationId)
        }
    }

    private suspend fun complete(
        task: StudyTask,
        notificationId: Int,
    ) {
        taskRepository.save(task.copy(completedAt = wallClock.now()))
        task.reminders.forEach { schedulingService.cancel(it.id) }
        notifier.cancel(notificationId)
    }

    private suspend fun snooze(
        task: StudyTask,
        reminderId: String,
        notificationId: Int,
    ) {
        val reminder = task.reminders.firstOrNull { it.id == reminderId } ?: return notifier.cancel(notificationId)
        val snoozed =
            reminder.copy(
                snooze =
                    SnoozeState(
                        until = wallClock.now() + SNOOZE_DURATION,
                        count =
                            (reminder.snooze?.count ?: 0) + 1,
                    ),
            )
        taskRepository.updateReminder(snoozed)
        val rescheduledTask = task.copy(reminders = task.reminders.map { if (it.id == reminderId) snoozed else it })
        schedulingService.schedule(rescheduledTask)
        notifier.cancel(notificationId)
    }

    private suspend fun startSession(
        task: StudyTask,
        notificationId: Int,
    ) {
        sessionRepository.execute(
            command = TimerCommand.Start(sessionId = idGenerator(), subjectId = task.subjectId, note = task.title),
            eventId = idGenerator(),
            anchor = currentAnchor(),
        )
        notifier.cancel(notificationId)
    }

    /**
     * A minimal, self-contained [TimeAnchor] for this one command.
     *
     * The app has no shared, injectable `AnchoredClock` implementation yet (the timer feature that
     * owns that wiring has not landed) — building the anchor inline here, rather than adding one,
     * is the smaller change; the boot id source is `Settings.Global.BOOT_COUNT`, which needs no
     * permission and changes exactly once per boot, same guarantee [BootId] documents.
     */
    private fun currentAnchor(): TimeAnchor =
        TimeAnchor(
            uptime = elapsedRealtimeSource.uptime(),
            wallClock = wallClock.now(),
            bootId = BootId(Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).toString()),
        )
}
