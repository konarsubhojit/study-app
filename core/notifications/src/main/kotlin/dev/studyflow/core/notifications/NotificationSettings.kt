package dev.studyflow.core.notifications

import android.content.Context
import android.content.Intent

/**
 * The system's answer to "what will StudyFlow actually be allowed to show me?".
 *
 * Read fresh every time the settings screen resumes: the user can leave for system settings, flip
 * a switch and come back, and a cached snapshot would then be a screen that lies.
 */
public data class NotificationSettingsSnapshot(
    val permission: NotificationPermissionState,
    val channels: List<NotificationChannelStatus>,
) {
    /** Nothing can be posted at all — the app-level switch or the permission is off. */
    val blockedAppWide: Boolean
        get() = !permission.canPost

    /** Channels the user has turned off while notifications as a whole are allowed. */
    val disabledChannels: List<NotificationChannelStatus>
        get() = if (blockedAppWide) emptyList() else channels.filterNot(NotificationChannelStatus::enabled)
}

/** Everything the in-app notification settings screen needs from the platform. */
public interface NotificationSettingsSource {
    /**
     * @param shouldShowRationale the hosting activity's answer to
     *   `ActivityCompat.shouldShowRequestPermissionRationale`. Only an activity can answer it, and
     *   it is what tells a first denial apart from a permanent one.
     */
    public fun snapshot(shouldShowRationale: Boolean = false): NotificationSettingsSnapshot

    /** System page for the app as a whole. */
    public fun appSettingsIntent(): Intent

    /** System page for one channel, so "Alarms are silent" is one tap from where it is stated. */
    public fun channelSettingsIntent(channel: StudyFlowNotificationChannel): Intent

    /** Records that the system permission dialog was shown, so a denial can be told from silence. */
    public fun recordPermissionRequested()
}

/** [NotificationSettingsSource] over the real platform. */
public class AndroidNotificationSettingsSource(
    private val context: Context,
    private val registrar: NotificationChannelRegistrar = NotificationChannelRegistrar(context),
    private val requestLog: NotificationPermissionRequestLog =
        SharedPreferencesNotificationPermissionRequestLog(context),
) : NotificationSettingsSource {
    override fun snapshot(shouldShowRationale: Boolean): NotificationSettingsSnapshot {
        // Registering first means a channel the user deleted reappears in the list instead of the
        // screen showing the app's defaults for something the system no longer knows about.
        registrar.register()
        val permissions =
            AndroidNotificationPermissionReader(
                context = context,
                requestLog = requestLog,
                shouldShowRationale = { shouldShowRationale },
            )
        return NotificationSettingsSnapshot(
            permission = permissions.currentState(),
            channels = registrar.statuses(),
        )
    }

    override fun appSettingsIntent(): Intent = registrar.appSettingsIntent()

    override fun channelSettingsIntent(channel: StudyFlowNotificationChannel): Intent =
        registrar.settingsIntent(channel)

    override fun recordPermissionRequested() {
        requestLog.recordRequested()
    }
}
