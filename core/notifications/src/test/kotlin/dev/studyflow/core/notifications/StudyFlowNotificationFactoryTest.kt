package dev.studyflow.core.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudyFlowNotificationFactoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val factory = StudyFlowNotificationFactory(context, SMALL_ICON)
    private val openTimer = StudyFlowPendingIntents.activity(context, 0, "studyflow://timer/running".toUri())

    @Test
    fun `the ongoing timer is a silent chronometer the system ticks by itself`() {
        val notification =
            factory.ongoingChronometer(
                title = "Studying",
                text = "Maths revision",
                startedAtEpochMillis = STARTED_AT,
                contentIntent = openTimer,
                actions = listOf(action("Pause"), action("Stop")),
            )

        assertEquals(StudyFlowNotificationChannel.STUDY_TIMER.id, notification.channelId)
        assertEquals(STARTED_AT, notification.`when`)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.CATEGORY_STOPWATCH, notification.category)
        assertEquals(listOf("Pause", "Stop"), notification.actions.map { it.title.toString() })
    }

    @Test
    fun `a countdown timer counts down`() {
        val notification =
            factory.ongoingChronometer(
                title = "Break",
                text = "5 minutes left",
                startedAtEpochMillis = STARTED_AT,
                contentIntent = openTimer,
                chronometer = ChronometerPresentation(countDown = true),
            )

        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
    }

    @Test
    fun `a paused timer remains ongoing without ticking`() {
        val notification =
            factory.ongoingChronometer(
                title = "Studying",
                text = "Timer paused",
                startedAtEpochMillis = STARTED_AT,
                contentIntent = openTimer,
                chronometer = ChronometerPresentation(usesChronometer = false),
            )

        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `upload progress is determinate, ongoing and silent while it runs`() {
        val notification =
            factory.progress(
                title = "Uploading lecture notes",
                text = "3 of 4 files",
                progress = NotificationProgress.Determinate(completed = 3, total = 4),
                contentIntent = null,
                actions = listOf(action("Cancel")),
            )

        assertEquals(StudyFlowNotificationChannel.UPLOADS.id, notification.channelId)
        assertEquals(4, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(3, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(1, notification.actions.size)
    }

    @Test
    fun `a finished upload can be dismissed and no longer shows a bar`() {
        val notification =
            factory.progress(
                title = "Upload complete",
                text = "4 files",
                progress = NotificationProgress.Finished,
                contentIntent = null,
            )

        assertEquals(0, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
    }

    @Test
    fun `unknown-size work shows an indeterminate bar`() {
        val notification =
            factory.progress(
                title = "Preparing upload",
                text = "Checking files",
                progress = NotificationProgress.Indeterminate,
                contentIntent = null,
            )

        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    fun `progress refuses an impossible ratio`() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationProgress.Determinate(completed = 5, total = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotificationProgress.Determinate(completed = 0, total = 0)
        }
    }

    @Test
    fun `a reminder is a dismissible alert on the reminders channel`() {
        val notification =
            factory.alert(
                channel = StudyFlowNotificationChannel.TASK_REMINDERS,
                title = "Revise chapter 4",
                text = "Due at 18:00",
                contentIntent = openTimer,
                presentation = AlertPresentation(whenEpochMillis = STARTED_AT),
            )

        assertEquals(StudyFlowNotificationChannel.TASK_REMINDERS.id, notification.channelId)
        assertEquals(Notification.CATEGORY_REMINDER, notification.category)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(null, notification.fullScreenIntent)
    }

    @Test
    fun `a reminder can carry the subject colour, a public fallback and a group key`() {
        val publicVersion =
            factory.alert(
                channel = StudyFlowNotificationChannel.TASK_REMINDERS,
                title = "You have a reminder",
                text = "",
                contentIntent = null,
            )

        val notification =
            factory.alert(
                channel = StudyFlowNotificationChannel.TASK_REMINDERS,
                title = "Revise chapter 4",
                text = "Due at 18:00",
                contentIntent = openTimer,
                presentation =
                    AlertPresentation(color = SUBJECT_COLOR, publicVersion = publicVersion, group = GROUP_KEY),
            )

        assertEquals(SUBJECT_COLOR, notification.color)
        assertEquals(GROUP_KEY, notification.group)
        assertEquals("You have a reminder", publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(
            "You have a reminder",
            notification.publicVersion
                ?.extras
                ?.getCharSequence(Notification.EXTRA_TITLE)
                .toString(),
        )
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun `a grouped reminder summary lists every reminder and never posts a heads-up alert per task`() {
        val summary =
            factory.groupedReminderSummary(
                title = "3 tasks need your attention",
                text = "3 reminders",
                lines = listOf("Revise chapter 4", "Submit essay", "Practice set 7"),
                contentIntent = openTimer,
                group = GROUP_KEY,
            )

        assertEquals(GROUP_KEY, summary.group)
        assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(StudyFlowNotificationChannel.TASK_REMINDERS.id, summary.channelId)
    }

    @Test
    fun `only the alarms channel may take over the screen`() {
        val alarm =
            factory.alert(
                channel = StudyFlowNotificationChannel.ALARMS,
                title = "Exam starts now",
                text = "Maths paper 1",
                contentIntent = openTimer,
                fullScreenIntent = openTimer,
            )

        assertEquals(Notification.CATEGORY_ALARM, alarm.category)
        assertEquals(openTimer, alarm.fullScreenIntent)

        assertThrows(IllegalArgumentException::class.java) {
            factory.alert(
                channel = StudyFlowNotificationChannel.TASK_REMINDERS,
                title = "Revise chapter 4",
                text = "Due at 18:00",
                contentIntent = openTimer,
                fullScreenIntent = openTimer,
            )
        }
    }

    @Test
    fun `every pending intent the app creates is immutable`() {
        val broadcast =
            StudyFlowPendingIntents.broadcast(
                context,
                requestCode = 1,
                intent = Intent("dev.studyflow.PAUSE").setPackage(context.packageName),
            )
        val service =
            StudyFlowPendingIntents.service(
                context,
                requestCode = 2,
                intent = Intent("dev.studyflow.STOP").setPackage(context.packageName),
            )

        listOf(openTimer, broadcast, service).forEach { pendingIntent ->
            val flags = shadowOf(pendingIntent).flags
            assertTrue("mutable PendingIntent", flags and PendingIntent.FLAG_IMMUTABLE != 0)
            assertEquals(0, flags and PendingIntent.FLAG_MUTABLE)
            assertTrue(flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
        }
    }

    @Test
    fun `deep links open this app and stay distinct per destination`() {
        val timer = StudyFlowPendingIntents.activity(context, 0, "studyflow://timer/running".toUri())
        val task = StudyFlowPendingIntents.activity(context, 0, "studyflow://tasks/42".toUri())

        val intent = shadowOf(timer).savedIntent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(context.packageName, intent.`package`)
        assertEquals("studyflow://timer/running".toUri(), intent.data)
        assertNotEquals(shadowOf(timer).savedIntent.data, shadowOf(task).savedIntent.data)
    }

    @Test
    fun `action intents must be explicit so no other app can receive them`() {
        assertThrows(IllegalArgumentException::class.java) {
            StudyFlowPendingIntents.broadcast(context, requestCode = 2, intent = Intent("dev.studyflow.PAUSE"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StudyFlowPendingIntents.service(context, requestCode = 3, intent = Intent("dev.studyflow.PAUSE"))
        }
    }

    private fun action(title: String) = NotificationAction(title, SMALL_ICON, openTimer)

    private companion object {
        const val SMALL_ICON = android.R.drawable.ic_dialog_info
        const val STARTED_AT = 1_772_000_000_000L
        const val SUBJECT_COLOR = 0xFF3366CC.toInt()
        const val GROUP_KEY = "studyflow.reminders"
    }
}
