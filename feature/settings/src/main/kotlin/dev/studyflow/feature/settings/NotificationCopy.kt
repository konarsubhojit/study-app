package dev.studyflow.feature.settings

import dev.studyflow.core.notifications.NotificationChannelStatus
import dev.studyflow.core.notifications.NotificationMessageKey
import dev.studyflow.core.notifications.StudyFlowNotificationChannel

/**
 * The words for the notification keys the core layer hands out.
 *
 * Kept in one object rather than inline in the composables so that the day this app ships a second
 * language, the move to `strings.xml` is a mechanical edit of this file and nothing else.
 */
internal object NotificationCopy {
    fun rationale(key: NotificationMessageKey): String =
        when (key) {
            NotificationMessageKey.RATIONALE_GENERIC -> {
                "StudyFlow needs permission to show notifications before it can tell you anything."
            }

            NotificationMessageKey.RATIONALE_TIMER -> {
                "Allow notifications so your study session keeps running and stays visible after you leave the app."
            }

            NotificationMessageKey.RATIONALE_REMINDER -> {
                "Allow notifications so StudyFlow can remind you about this task at the time you chose."
            }

            NotificationMessageKey.RATIONALE_ALARM -> {
                "Allow notifications so an alarm can reach you even when your phone is idle."
            }

            NotificationMessageKey.RATIONALE_UPLOAD -> {
                "Allow notifications to see upload progress and to be told if an upload fails."
            }

            else -> {
                degradation(key)
            }
        }

    fun degradation(key: NotificationMessageKey): String =
        when (key) {
            NotificationMessageKey.DEGRADED_TIMER -> {
                "Your session still runs, but Android will not show it. Open StudyFlow to see the timer."
            }

            NotificationMessageKey.DEGRADED_REMINDER -> {
                "Reminders are still saved and shown in the app, but they cannot appear on your screen."
            }

            NotificationMessageKey.DEGRADED_ALARM -> {
                "Alarms cannot ring or take over the screen while notifications are off."
            }

            NotificationMessageKey.DEGRADED_UPLOAD -> {
                "Uploads still finish in the background, but you will not see progress or failures."
            }

            else -> {
                "Notifications are off, so StudyFlow cannot tell you about anything that happens."
            }
        }

    fun channelPurpose(channel: StudyFlowNotificationChannel): String = channel.description

    fun channelState(status: NotificationChannelStatus): String =
        when {
            !status.enabled -> "Turned off in system settings"
            status.importanceLoweredByUser -> "Shown quietly — you turned this down"
            else -> "On"
        }
}
