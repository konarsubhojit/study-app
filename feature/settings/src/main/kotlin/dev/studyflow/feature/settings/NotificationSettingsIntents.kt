package dev.studyflow.feature.settings

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri

internal fun Context.requestExactAlarmPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        start(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData("package:$packageName".toUri()),
        )
    }
}

/** The picker's own `EXTRA_RINGTONE_PICKED_URI`, wherever `RingtoneManager` put it in [this]. */
internal fun Intent.getParcelableRingtoneUri(): android.net.Uri? =
    @Suppress("DEPRECATION")
    getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)

internal fun ringtonePickerIntent(currentUri: String): Intent =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        putExtra(
            RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        )
        if (currentUri.isNotBlank()) {
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri.toUri())
        }
    }

/**
 * Asks the platform whether a refusal can still be explained.
 *
 * `false` outside an activity and below Android 13, which is correct in both cases: there is
 * nothing to explain when there is no permission to ask for, and no window to explain it in.
 */
internal fun Activity?.shouldExplainNotifications(): Boolean =
    this != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)

/**
 * Opens a system settings page, tolerating the ones that do not exist.
 *
 * Per-channel settings are missing on some OEM builds. Losing the shortcut is survivable; crashing
 * on the way to a settings screen is not, so the app-level page is tried next and a device with
 * neither simply leaves the user where they were.
 */
internal fun Context.startSettings(effect: NotificationSettingsUiEffect.OpenSystemSettings) {
    if (start(effect.intent)) return
    effect.fallbackIntent?.let(::start)
}

private fun Context.start(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
