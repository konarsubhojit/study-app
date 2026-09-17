package dev.studyflow.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationChannelRegistrarTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = NotificationManagerCompat.from(context)

    @Test
    fun `every declared channel and group exists after registration`() {
        NotificationChannelRegistrar(context, manager).register()

        StudyFlowChannelGroup.entries.forEach { group ->
            assertNotNull("${group.id} was not created", manager.getNotificationChannelGroupCompat(group.id))
        }
        StudyFlowNotificationChannel.entries.forEach { channel ->
            val created = manager.getNotificationChannelCompat(channel.id)
            assertNotNull("${channel.id} was not created", created)
            assertEquals(channel.importance, created?.importance)
            assertEquals(channel.group.id, created?.group)
        }
    }

    @Test
    fun `registration is idempotent and never overwrites what the user chose`() {
        val registrar = NotificationChannelRegistrar(context, manager)
        registrar.register()
        lowerImportanceAsUser(StudyFlowNotificationChannel.ALARMS)

        registrar.register()

        assertEquals(
            NotificationManagerCompat.IMPORTANCE_LOW,
            manager.getNotificationChannelCompat(StudyFlowNotificationChannel.ALARMS.id)?.importance,
        )
        assertEquals(StudyFlowNotificationChannel.entries.size, manager.notificationChannelsCompat.size)
    }

    @Test
    fun `a channel turned off by the user is reported as disabled`() {
        val registrar = NotificationChannelRegistrar(context, manager)
        registrar.register()

        assertTrue(registrar.statusOf(StudyFlowNotificationChannel.UPLOADS).enabled)

        setImportance(StudyFlowNotificationChannel.UPLOADS, NotificationManagerCompat.IMPORTANCE_NONE)
        val status = registrar.statusOf(StudyFlowNotificationChannel.UPLOADS)

        assertFalse(status.enabled)
        assertTrue(status.registered)
    }

    @Test
    fun `an unregistered channel reports the app default without pretending it exists`() {
        val status = NotificationChannelRegistrar(context, manager).statusOf(StudyFlowNotificationChannel.STUDY_TIMER)

        assertFalse(status.registered)
        assertEquals(StudyFlowNotificationChannel.STUDY_TIMER.importance, status.importance)
        assertFalse(status.importanceLoweredByUser)
    }

    @Test
    fun `legacy channels are deleted so a rename does not leave a stale switch behind`() {
        val legacy = "studyflow.channel.old_timer"
        setImportance(legacy, NotificationManagerCompat.IMPORTANCE_DEFAULT)

        NotificationChannelRegistrar(context, manager, legacyChannelIds = setOf(legacy)).register()

        assertNull(manager.getNotificationChannelCompat(legacy))
    }

    @Test
    fun `settings intents point at this app's channels`() {
        val registrar = NotificationChannelRegistrar(context, manager)

        val channelIntent = registrar.settingsIntent(StudyFlowNotificationChannel.TASK_REMINDERS)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channelIntent.action)
        assertEquals(context.packageName, channelIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(
            StudyFlowNotificationChannel.TASK_REMINDERS.id,
            channelIntent.getStringExtra(Settings.EXTRA_CHANNEL_ID),
        )

        val appIntent = registrar.appSettingsIntent()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, appIntent.action)
        assertEquals(context.packageName, appIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    private fun lowerImportanceAsUser(channel: StudyFlowNotificationChannel) {
        setImportance(channel, NotificationManagerCompat.IMPORTANCE_LOW)
    }

    private fun setImportance(
        channel: StudyFlowNotificationChannel,
        importance: Int,
    ) = setImportance(channel.id, importance)

    private fun setImportance(
        channelId: String,
        importance: Int,
    ) {
        context
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(channelId, channelId, importance))
    }
}
