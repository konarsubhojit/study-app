package dev.studyflow.core.scheduling

import androidx.core.app.NotificationManagerCompat
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.domain.reminder.SchedulingCapabilities
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.ReminderPrecision
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subject
import dev.studyflow.core.notifications.NotificationChannelRegistrar
import dev.studyflow.core.notifications.NotificationPermissionState
import dev.studyflow.core.notifications.NotificationPermissionStatus
import dev.studyflow.core.notifications.StudyFlowNotificationChannel
import dev.studyflow.core.notifications.StudyFlowNotificationFactory
import dev.studyflow.core.notifications.StudyFlowNotifier
import dev.studyflow.core.testing.data.FakeSubjectRepository
import dev.studyflow.core.testing.data.FakeTaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
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
class ReminderDeliveryCoordinatorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = NotificationManagerCompat.from(context)
    private val notifier =
        StudyFlowNotifier(
            registrar = NotificationChannelRegistrar(context, manager),
            permissions = {
                NotificationPermissionState(
                    NotificationPermissionStatus.GRANTED,
                    notificationsEnabled = true,
                )
            },
            manager = manager,
        )
    private val notificationFactory = StudyFlowNotificationFactory(context, android.R.drawable.ic_dialog_info)
    private val taskRepository = FakeTaskRepository(now = NOW)
    private val subjectRepository = FakeSubjectRepository()
    private var digestEnabled = false
    private var fullScreenIntentAllowed = true
    private val coordinator =
        ReminderDeliveryCoordinator(
            context = context,
            taskRepository = taskRepository,
            subjectRepository = subjectRepository,
            notifier = notifier,
            notificationFactory = notificationFactory,
            digestEnabled = { digestEnabled },
            capabilitiesProvider = { SchedulingCapabilities(canUseFullScreenIntent = fullScreenIntentAllowed) },
            clock = Clock { NOW },
        )

    @Test
    fun `an open task's reminder is posted with its title, due time and subject colour`() =
        runBlocking {
            subjectRepository.put(Subject(id = "subject-1", name = "Maths", colorArgb = SUBJECT_COLOR))
            taskRepository.save(task())

            coordinator.deliver("reminder-1", "task-1")

            val notification =
                shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
                    .getNotification(reminderNotificationId("reminder-1"))
            assertEquals(
                "Revise chapter 4",
                notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString(),
            )
            assertEquals(SUBJECT_COLOR, notification.color)
            assertEquals(3, notification.actions.size)
        }

    @Test
    fun `a posted reminder records that it fired`() =
        runBlocking {
            taskRepository.save(task())

            coordinator.deliver("reminder-1", "task-1")

            val updatedReminder =
                taskRepository
                    .observeTask("task-1")
                    .first()!!
                    .reminders
                    .single()
            assertEquals(NOW, updatedReminder.lastFiredAt)
        }

    @Test
    fun `a completed task cancels rather than posts its reminder`() =
        runBlocking {
            taskRepository.save(task().copy(completedAt = NOW))

            coordinator.deliver("reminder-1", "task-1")

            assertEquals(0, shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).size())
        }

    @Test
    fun `a deleted task cancels rather than posts its reminder`() =
        runBlocking {
            taskRepository.save(task())
            taskRepository.delete("task-1", NOW)

            coordinator.deliver("reminder-1", "task-1")

            assertEquals(0, shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).size())
        }

    @Test
    fun `a reminder that no longer exists on its task is not posted`() =
        runBlocking {
            taskRepository.save(task().copy(reminders = emptyList()))

            coordinator.deliver("reminder-1", "task-1")

            assertEquals(0, shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).size())
        }

    @Test
    fun `when the digest is enabled individual reminders stay silent`() =
        runBlocking {
            digestEnabled = true
            taskRepository.save(task())

            coordinator.deliver("reminder-1", "task-1")

            assertEquals(0, shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).size())
        }

    @Test
    fun `an alarm-style reminder is exempt from the digest`() =
        runBlocking {
            digestEnabled = true
            taskRepository.save(task(precision = ReminderPrecision.ALARM))

            coordinator.deliver("reminder-1", "task-1")

            assertEquals(1, shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).size())
        }

    @Test
    fun `an alarm-style reminder posts to the ALARMS channel with a full-screen intent`() =
        runBlocking {
            taskRepository.save(task(precision = ReminderPrecision.ALARM))

            coordinator.deliver("reminder-1", "task-1")

            val notification =
                shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
                    .getNotification(reminderNotificationId("reminder-1"))
            assertEquals(StudyFlowNotificationChannel.ALARMS.id, notification.channelId)
            assertTrue(notification.fullScreenIntent != null)
        }

    @Test
    fun `an alarm-style reminder falls back to a heads-up notification without full-screen capability`() =
        runBlocking {
            fullScreenIntentAllowed = false
            taskRepository.save(task(precision = ReminderPrecision.ALARM))

            coordinator.deliver("reminder-1", "task-1")

            val notification =
                shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
                    .getNotification(reminderNotificationId("reminder-1"))
            assertEquals(StudyFlowNotificationChannel.ALARMS.id, notification.channelId)
            assertNull(notification.fullScreenIntent)
        }

    @Test
    fun `ten simultaneous reminders collapse into one grouped summary`() =
        runBlocking {
            repeat(10) { index ->
                taskRepository.save(task(id = "task-$index", reminderId = "reminder-$index"))
            }

            repeat(10) { index -> coordinator.deliver("reminder-$index", "task-$index") }

            val shadowManager = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            // Ten individual reminders plus exactly one summary — never ten heads-up alerts alone.
            assertEquals(11, shadowManager.size())
            val summary = shadowManager.getNotification(GROUP_SUMMARY_NOTIFICATION_ID)
            assertTrue(summary.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0)
        }

    private fun task(
        id: String = "task-1",
        reminderId: String = "reminder-1",
        precision: ReminderPrecision = ReminderPrecision.GENTLE,
    ): StudyTask =
        StudyTask(
            id = id,
            title = "Revise chapter 4",
            subjectId = "subject-1",
            dueAt = LocalDateTime(2026, 3, 2, 18, 0),
            timeZone = TimeZone.UTC,
            reminders = listOf(Reminder(id = reminderId, taskId = id, precision = precision)),
            updatedAt = NOW,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-02T06:00:00Z")
        const val SUBJECT_COLOR = 0xFF3366CC.toInt()
    }
}
