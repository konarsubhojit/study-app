package dev.studyflow.core.scheduling

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.studyflow.core.domain.reminder.ReminderPlan
import dev.studyflow.core.domain.reminder.SchedulingCapabilities
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.SnoozeState
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.notifications.NotificationChannelRegistrar
import dev.studyflow.core.notifications.NotificationPermissionState
import dev.studyflow.core.notifications.NotificationPermissionStatus
import dev.studyflow.core.notifications.StudyFlowNotificationChannel
import dev.studyflow.core.notifications.StudyFlowNotificationFactory
import dev.studyflow.core.notifications.StudyFlowNotifier
import dev.studyflow.core.testing.data.FakeSessionRepository
import dev.studyflow.core.testing.data.FakeSubjectRepository
import dev.studyflow.core.testing.data.FakeTaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderActionExecutorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = NotificationManagerCompat.from(context)
    private val grantedPermission =
        NotificationPermissionState(NotificationPermissionStatus.GRANTED, notificationsEnabled = true)
    private val notifier =
        StudyFlowNotifier(
            registrar = NotificationChannelRegistrar(context, manager),
            permissions = { grantedPermission },
            manager = manager,
        )
    private val taskRepository = FakeTaskRepository(now = NOW)
    private val sessionRepository = FakeSessionRepository()
    private val platformScheduler = RecordingPlatformScheduler()
    private val schedulingService =
        ReminderSchedulingService(
            capabilitiesProvider = { SchedulingCapabilities() },
            platformScheduler = platformScheduler,
        ) { NOW }
    private val groupSummary =
        ReminderDeliveryCoordinator(
            context = context,
            taskRepository = taskRepository,
            subjectRepository = FakeSubjectRepository(),
            notifier = notifier,
            notificationFactory = StudyFlowNotificationFactory(context, android.R.drawable.ic_dialog_info),
        )
    private val executor =
        ReminderActionExecutor(
            context = context,
            taskRepository = taskRepository,
            schedulingService = schedulingService,
            sessionRepository = sessionRepository,
            notifier = notifier,
            groupSummary = groupSummary,
            wallClock = { NOW },
            idGenerator = idSequence(),
        )

    @Test
    fun `complete marks the task done and cancels its reminders and notification`() =
        runBlocking {
            taskRepository.save(task())
            notifier.post(
                reminderNotificationId("reminder-1"),
                StudyFlowNotificationChannel.TASK_REMINDERS,
                dummyNotification(),
            )

            executor.execute(ReminderActionKind.COMPLETE, "reminder-1", "task-1")

            val saved = taskRepository.observeTask("task-1").first()!!
            assertNotNull(saved.completedAt)
            assertTrue(platformScheduler.calls.contains("cancel:reminder-1"))
            assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).size())
        }

    @Test
    fun `snooze postpones the reminder by ten minutes and reschedules it`() =
        runBlocking {
            taskRepository.save(task())

            executor.execute(ReminderActionKind.SNOOZE, "reminder-1", "task-1")

            val saved = taskRepository.observeTask("task-1").first()!!
            val reminder = saved.reminders.single()
            assertEquals(NOW + SNOOZE_DURATION, reminder.snooze?.until)
            assertEquals(1, reminder.snooze?.count)
            assertTrue(platformScheduler.calls.contains("inexact:reminder-1"))
        }

    @Test
    fun `snoozing twice increments the snooze count`() =
        runBlocking {
            taskRepository.save(task(snooze = SnoozeState(until = NOW, count = 1)))

            executor.execute(ReminderActionKind.SNOOZE, "reminder-1", "task-1")

            val reminder =
                taskRepository
                    .observeTask("task-1")
                    .first()!!
                    .reminders
                    .single()
            assertEquals(2, reminder.snooze?.count)
        }

    @Test
    fun `start session begins a timer tagged with the task title and its subject`() =
        runBlocking {
            taskRepository.save(task())

            executor.execute(ReminderActionKind.START_SESSION, "reminder-1", "task-1")

            val command = sessionRepository.executedCommands.single() as TimerCommand.Start
            assertEquals("Revise chapter 4", command.note)
            assertEquals("subject-1", command.subjectId)
        }

    @Test
    fun `an action for a task that no longer exists just clears the notification`() =
        runBlocking {
            executor.execute(ReminderActionKind.COMPLETE, "reminder-1", "missing-task")

            assertNull(sessionRepository.executedCommands.firstOrNull())
        }

    @Test
    fun `completing one of a grouped pair refreshes the summary down to a single reminder`() =
        runBlocking {
            taskRepository.save(task())
            taskRepository.save(
                task().copy(id = "task-2", reminders = listOf(Reminder(id = "reminder-2", taskId = "task-2"))),
            )
            groupSummary.deliver("reminder-1", "task-1")
            groupSummary.deliver("reminder-2", "task-2")
            val shadowManager = shadowOf(context.getSystemService(NotificationManager::class.java))
            assertEquals(3, shadowManager.size())

            executor.execute(ReminderActionKind.COMPLETE, "reminder-1", "task-1")

            // One reminder left is below the grouping threshold, so the summary is torn down too.
            assertEquals(1, shadowManager.size())
        }

    private fun task(snooze: SnoozeState? = null): StudyTask =
        StudyTask(
            id = "task-1",
            title = "Revise chapter 4",
            subjectId = "subject-1",
            dueAt = LocalDateTime(2026, 3, 2, 18, 0),
            timeZone = TimeZone.UTC,
            reminders = listOf(Reminder(id = "reminder-1", taskId = "task-1", snooze = snooze)),
            updatedAt = NOW,
        )

    private fun dummyNotification(): Notification =
        NotificationCompat
            .Builder(context, StudyFlowNotificationChannel.TASK_REMINDERS.id)
            .setContentTitle("x")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

    private fun idSequence(): () -> String {
        var next = 0
        return { "id-${next++}" }
    }

    private class RecordingPlatformScheduler : ReminderPlatformScheduler {
        val calls = mutableListOf<String>()

        override fun scheduleInexact(plan: ReminderPlan): PlatformScheduleOutcome {
            calls += "inexact:${plan.reminderId}"
            return PlatformScheduleOutcome.SCHEDULED
        }

        override fun scheduleExact(plan: ReminderPlan): PlatformScheduleOutcome {
            calls += "exact:${plan.reminderId}"
            return PlatformScheduleOutcome.SCHEDULED
        }

        override fun scheduleAlarmClock(plan: ReminderPlan): PlatformScheduleOutcome {
            calls += "alarm:${plan.reminderId}"
            return PlatformScheduleOutcome.SCHEDULED
        }

        override fun cancel(reminderId: String) {
            calls += "cancel:$reminderId"
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-02T06:00:00Z")
    }
}
