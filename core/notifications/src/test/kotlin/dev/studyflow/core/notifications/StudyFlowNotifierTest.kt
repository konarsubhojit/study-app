package dev.studyflow.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudyFlowNotifierTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = NotificationManagerCompat.from(context)
    private val shadowManager = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val factory = StudyFlowNotificationFactory(context, android.R.drawable.ic_dialog_info)
    private var permissionState =
        NotificationPermissionState(NotificationPermissionStatus.GRANTED, notificationsEnabled = true)
    private val notifier =
        StudyFlowNotifier(
            registrar = NotificationChannelRegistrar(context, manager),
            permissions = { permissionState },
            manager = manager,
        )

    @Test
    fun `posting creates the channel it needs and shows the notification`() {
        val result = notifier.post(ID, StudyFlowNotificationChannel.TASK_REMINDERS, reminder())

        assertEquals(NotificationPostResult.POSTED, result)
        assertTrue(result.posted)
        assertEquals(1, shadowManager.size())
        assertEquals(
            StudyFlowNotificationChannel.TASK_REMINDERS.id,
            shadowManager.getNotification(ID).channelId,
        )
    }

    @Test
    fun `a denied permission is reported instead of crashing or silently doing nothing`() {
        permissionState =
            NotificationPermissionState(
                NotificationPermissionStatus.PERMANENTLY_DENIED,
                notificationsEnabled = false,
            )

        val result = notifier.post(ID, StudyFlowNotificationChannel.TASK_REMINDERS, reminder())

        assertEquals(NotificationPostResult.PERMISSION_DENIED, result)
        assertFalse(notifier.canPost(StudyFlowNotificationChannel.TASK_REMINDERS))
        assertEquals(0, shadowManager.size())
    }

    @Test
    fun `a channel the user switched off is reported as such, not as a permission problem`() {
        NotificationChannelRegistrar(context, manager).register()
        context
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    StudyFlowNotificationChannel.UPLOADS.id,
                    "Uploads",
                    NotificationManagerCompat.IMPORTANCE_NONE,
                ),
            )

        val result =
            notifier.post(
                ID,
                StudyFlowNotificationChannel.UPLOADS,
                factory.progress(
                    title = "Uploading",
                    text = "1 of 2",
                    progress = NotificationProgress.Determinate(1, 2),
                    contentIntent = null,
                ),
            )

        assertEquals(NotificationPostResult.CHANNEL_DISABLED, result)
        assertEquals(0, shadowManager.size())
    }

    @Test
    fun `cancelling removes a notification the user no longer needs`() {
        notifier.post(ID, StudyFlowNotificationChannel.TASK_REMINDERS, reminder())

        notifier.cancel(ID)

        assertEquals(0, shadowManager.size())
    }

    private fun reminder() =
        factory.alert(
            channel = StudyFlowNotificationChannel.TASK_REMINDERS,
            title = "Revise chapter 4",
            text = "Due at 18:00",
            contentIntent = null,
        )

    private companion object {
        const val ID = 42
    }
}
