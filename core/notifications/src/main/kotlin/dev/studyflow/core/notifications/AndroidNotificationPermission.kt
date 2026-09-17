package dev.studyflow.core.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/** Reads the live permission state; implementations must not cache across a settings change. */
public fun interface NotificationPermissionReader {
    public fun currentState(): NotificationPermissionState
}

/**
 * Remembers whether the system dialog has ever been shown.
 *
 * Android cannot answer this: `shouldShowRequestPermissionRationale` is `false` both before the
 * first request and after a permanent denial, so without a record of our own the two opposite
 * states are indistinguishable and the app would either never ask or ask forever. This is
 * permission bookkeeping rather than a user setting, which is why it does not live in the Proto
 * DataStore that holds preferences the user can see and change.
 */
public interface NotificationPermissionRequestLog {
    public fun hasRequested(): Boolean

    public fun recordRequested()
}

/** [NotificationPermissionRequestLog] backed by a private, app-local preferences file. */
public class SharedPreferencesNotificationPermissionRequestLog(
    context: Context,
) : NotificationPermissionRequestLog {
    private val preferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun hasRequested(): Boolean = preferences.getBoolean(KEY_REQUESTED, false)

    override fun recordRequested() {
        preferences.edit { putBoolean(KEY_REQUESTED, true) }
    }

    private companion object {
        const val FILE_NAME = "studyflow.notification-permission"
        const val KEY_REQUESTED = "post-notifications-requested"
    }
}

/**
 * Derives [NotificationPermissionState] from the platform.
 *
 * [shouldShowRationale] comes from the hosting activity, which is the only place that can answer
 * it; passing it in keeps this class usable from a service or a worker, which can read everything
 * else but must never prompt.
 */
public class AndroidNotificationPermissionReader(
    context: Context,
    private val requestLog: NotificationPermissionRequestLog =
        SharedPreferencesNotificationPermissionRequestLog(context),
    private val shouldShowRationale: () -> Boolean = { false },
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : NotificationPermissionReader {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    override fun currentState(): NotificationPermissionState =
        NotificationPermissionState(
            status = status(),
            notificationsEnabled = manager.areNotificationsEnabled(),
        )

    private fun status(): NotificationPermissionStatus =
        when {
            sdkInt < Build.VERSION_CODES.TIRAMISU -> NotificationPermissionStatus.NOT_REQUIRED
            isGranted() -> NotificationPermissionStatus.GRANTED
            shouldShowRationale() -> NotificationPermissionStatus.DENIED
            !requestLog.hasRequested() -> NotificationPermissionStatus.NOT_REQUESTED
            else -> NotificationPermissionStatus.PERMANENTLY_DENIED
        }

    // POST_NOTIFICATIONS was added in API 33; ContextCompat.checkSelfPermission safely handles
    // unrecognised permission strings on older platforms, and [status] only calls this after
    // confirming sdkInt is at least TIRAMISU.
    @SuppressLint("InlinedApi")
    private fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
