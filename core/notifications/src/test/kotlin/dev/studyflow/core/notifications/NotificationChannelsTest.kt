package dev.studyflow.core.notifications

import androidx.core.app.NotificationManagerCompat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("StudyFlowNotificationChannel")
class NotificationChannelsTest {
    @Test
    fun `channel and group ids are unique`() {
        val channelIds = StudyFlowNotificationChannel.entries.map(StudyFlowNotificationChannel::id)
        val groupIds = StudyFlowChannelGroup.entries.map(StudyFlowChannelGroup::id)

        assertEquals(channelIds.size, channelIds.toSet().size)
        assertEquals(groupIds.size, groupIds.toSet().size)
    }

    @Test
    fun `every channel documents itself`() {
        StudyFlowNotificationChannel.entries.forEach { channel ->
            assertTrue(channel.channelName.isNotBlank(), "${channel.id} has no name")
            assertTrue(channel.description.isNotBlank(), "${channel.id} has no description")
            assertTrue(
                channel.importance in
                    NotificationManagerCompat.IMPORTANCE_MIN..NotificationManagerCompat.IMPORTANCE_HIGH,
                "${channel.id} has an importance outside the documented range",
            )
        }
    }

    @Test
    fun `importance matches what interrupting the user is worth`() {
        assertEquals(NotificationManagerCompat.IMPORTANCE_LOW, StudyFlowNotificationChannel.STUDY_TIMER.importance)
        assertEquals(
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
            StudyFlowNotificationChannel.TASK_REMINDERS.importance,
        )
        assertEquals(NotificationManagerCompat.IMPORTANCE_HIGH, StudyFlowNotificationChannel.ALARMS.importance)
        assertEquals(NotificationManagerCompat.IMPORTANCE_MIN, StudyFlowNotificationChannel.UPLOADS.importance)
    }

    @Test
    fun `only alarms may take over the screen`() {
        val fullScreen = StudyFlowNotificationChannel.entries.filter(StudyFlowNotificationChannel::usesFullScreenIntent)

        assertEquals(listOf(StudyFlowNotificationChannel.ALARMS), fullScreen)
    }

    @Test
    fun `ongoing and progress channels stay silent`() {
        assertTrue(StudyFlowNotificationChannel.STUDY_TIMER.isSilentByDefault)
        assertTrue(StudyFlowNotificationChannel.UPLOADS.isSilentByDefault)
        assertFalse(StudyFlowNotificationChannel.TASK_REMINDERS.isSilentByDefault)
        assertFalse(StudyFlowNotificationChannel.ALARMS.isSilentByDefault)
    }

    @Test
    fun `channel ids resolve back to their declaration`() {
        StudyFlowNotificationChannel.entries.forEach { channel ->
            assertEquals(channel, StudyFlowNotificationChannel.fromId(channel.id))
        }
        assertEquals(null, StudyFlowNotificationChannel.fromId("some.other.app.channel"))
    }
}
