package dev.studyflow.core.notifications

import android.app.Notification
import androidx.core.app.NotificationManagerCompat
import dev.studyflow.core.common.logging.AppLogger

/** The honest outcome of posting a notification — success is only one of the options. */
public enum class NotificationPostResult {
    POSTED,

    /** `POST_NOTIFICATIONS` is missing, or notifications are off app-wide. */
    PERMISSION_DENIED,

    /** The permission is held, but the user turned this channel (or its group) off. */
    CHANNEL_DISABLED,
    ;

    public val posted: Boolean
        get() = this == POSTED
}

/**
 * The single way the app posts a notification (issue #24).
 *
 * `NotificationManagerCompat.notify` throws `SecurityException` without the permission on Android
 * 13+ and does nothing at all when the channel is off. Neither outcome may reach the user as a
 * crash or as silence, so this returns [NotificationPostResult]: the caller can show a banner
 * ("your session is running, but Android is not showing it"), and the acceptance criterion
 * "degrades visibly and gracefully" becomes something a test can check.
 *
 * Missing channels are recreated before posting, because a notification sent to a channel the user
 * deleted is dropped by the system with no error at all — the worst kind of silent no-op.
 */
public class StudyFlowNotifier(
    private val registrar: NotificationChannelRegistrar,
    private val permissions: NotificationPermissionReader,
    private val manager: NotificationManagerCompat,
    private val logger: AppLogger? = null,
) {
    public fun post(
        id: Int,
        channel: StudyFlowNotificationChannel,
        notification: Notification,
    ): NotificationPostResult {
        val blocked = blockedReason(channel)
        if (blocked != null) {
            report(channel, blocked, null)
            return blocked
        }

        return try {
            manager.notify(id, notification)
            NotificationPostResult.POSTED
        } catch (exception: SecurityException) {
            // The permission was revoked between the check above and this call. That is a denial,
            // not a crash: report it the same way and let the caller degrade.
            report(channel, NotificationPostResult.PERMISSION_DENIED, exception)
            NotificationPostResult.PERMISSION_DENIED
        }
    }

    public fun cancel(id: Int) {
        manager.cancel(id)
    }

    /** Whether a notification posted to [channel] right now would reach the user. */
    public fun canPost(channel: StudyFlowNotificationChannel): Boolean = blockedReason(channel) == null

    private fun blockedReason(channel: StudyFlowNotificationChannel): NotificationPostResult? {
        if (!permissions.currentState().canPost) return NotificationPostResult.PERMISSION_DENIED
        val status = registrar.statusOf(channel)
        if (status.registered) {
            return if (status.enabled) null else NotificationPostResult.CHANNEL_DISABLED
        }
        registrar.register()
        return if (registrar.statusOf(channel).enabled) null else NotificationPostResult.CHANNEL_DISABLED
    }

    private fun report(
        channel: StudyFlowNotificationChannel,
        result: NotificationPostResult,
        exception: Throwable?,
    ) {
        logger?.warning(TAG, "Notification on ${channel.id} not shown: $result", exception)
    }

    private companion object {
        const val TAG = "Notifications"
    }
}
