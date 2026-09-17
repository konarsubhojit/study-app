package dev.studyflow.core.notifications

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationChannelGroupCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Creates the channel groups and channels declared in [StudyFlowNotificationChannel].
 *
 * Registration is idempotent and deliberately *not* an update: `createNotificationChannel` leaves
 * an existing channel's importance, sound and vibration alone, so re-running this on every start
 * restores a channel the user deleted without undoing a channel the user merely turned down. A
 * setting the user changed and the app silently reverted is the worst outcome available here.
 *
 * Renaming a channel therefore requires a new id plus a [legacyChannelIds] entry, which is the
 * only sanctioned way for the app to delete a channel.
 */
public class NotificationChannelRegistrar(
    private val context: Context,
    private val manager: NotificationManagerCompat = NotificationManagerCompat.from(context),
    private val legacyChannelIds: Set<String> = emptySet(),
) {
    public fun register() {
        manager.createNotificationChannelGroupsCompat(
            StudyFlowChannelGroup.entries.map { group ->
                NotificationChannelGroupCompat
                    .Builder(group.id)
                    .setName(group.groupName)
                    .build()
            },
        )

        manager.createNotificationChannelsCompat(
            StudyFlowNotificationChannel.entries.map(::channel),
        )

        legacyChannelIds.forEach(manager::deleteNotificationChannel)
    }

    /** System state for one channel, as the settings screen and the notifier both need it. */
    public fun statusOf(channel: StudyFlowNotificationChannel): NotificationChannelStatus {
        val registered = manager.getNotificationChannelCompat(channel.id)
        val importance = registered?.importance ?: channel.importance
        val groupBlocked = manager.getNotificationChannelGroupCompat(channel.group.id)?.isBlocked == true
        return NotificationChannelStatus(
            channel = channel,
            registered = registered != null,
            importance = importance,
            groupBlocked = groupBlocked,
            appNotificationsEnabled = manager.areNotificationsEnabled(),
        )
    }

    public fun statuses(): List<NotificationChannelStatus> = StudyFlowNotificationChannel.entries.map(::statusOf)

    /** Deep link into this channel's own system settings page. */
    public fun settingsIntent(channel: StudyFlowNotificationChannel): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Deep link into the app-level notification settings page. */
    public fun appSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun channel(channel: StudyFlowNotificationChannel): NotificationChannelCompat =
        NotificationChannelCompat
            .Builder(channel.id, channel.importance)
            .setName(channel.channelName)
            .setDescription(channel.description)
            .setGroup(channel.group.id)
            .setShowBadge(channel.showsBadge)
            .setVibrationEnabled(channel.vibrates)
            .build()
}

/**
 * What the system currently says about a channel.
 *
 * [enabled] folds the three ways a notification can be suppressed — the app switch, the group
 * switch and the channel's own importance — into the single question every caller actually asks.
 */
public data class NotificationChannelStatus(
    val channel: StudyFlowNotificationChannel,
    val registered: Boolean,
    val importance: Int,
    val groupBlocked: Boolean,
    val appNotificationsEnabled: Boolean,
) {
    val enabled: Boolean
        get() =
            appNotificationsEnabled &&
                !groupBlocked &&
                importance != NotificationManagerCompat.IMPORTANCE_NONE

    /** True when the user turned this channel down rather than the app choosing a lower importance. */
    val importanceLoweredByUser: Boolean
        get() = registered && importance < channel.importance
}
