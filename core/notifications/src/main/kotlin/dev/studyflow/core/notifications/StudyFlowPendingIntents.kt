package dev.studyflow.core.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Every `PendingIntent` the app hands to the system, in one auditable place (issue #24).
 *
 * All of them are `FLAG_IMMUTABLE`. A mutable `PendingIntent` lets whoever holds it fill in the
 * blanks of an intent that is then sent *as this app*, which is why Android 12 made the flag
 * mandatory; `FLAG_MUTABLE` is only needed for things StudyFlow does not do (direct reply,
 * bubbles, slices). There is deliberately no helper here that produces one, so "is anything
 * mutable?" is answered by grepping for `FLAG_MUTABLE` and finding nothing.
 *
 * `FLAG_UPDATE_CURRENT` keeps a re-posted notification pointing at fresh extras rather than at the
 * first session's; the request code plus the intent's data URI is what keeps two live
 * notifications from sharing one `PendingIntent`.
 */
public object StudyFlowPendingIntents {
    private const val IMMUTABLE_UPDATE: Int = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /**
     * Opens the app at [deepLink], which must be a `studyflow://` URI the manifest already claims.
     *
     * The URI is also the intent's data, so distinct destinations never collapse onto one another
     * even if a caller reuses [requestCode].
     */
    public fun activity(
        context: Context,
        requestCode: Int,
        deepLink: Uri,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(Intent.ACTION_VIEW, deepLink)
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            IMMUTABLE_UPDATE,
        )

    /**
     * Fires an action button without opening the UI — "pause", "stop", "cancel upload".
     *
     * [intent] must be explicit (a component or the app's own package); an implicit broadcast from
     * a notification action would be readable by any app on the device.
     */
    public fun broadcast(
        context: Context,
        requestCode: Int,
        intent: Intent,
    ): PendingIntent {
        require(intent.component != null || intent.`package` != null) {
            "Notification action broadcasts must be explicit; set a component or a package."
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, IMMUTABLE_UPDATE)
    }

    /**
     * Opens an explicit activity outside the app's own deep-link scheme — used for the alarm-style
     * reminder's `fullScreenIntent` (issue #48), which must launch a specific activity in
     * `:core:scheduling` rather than resolve through `studyflow://` like [activity] does.
     */
    public fun explicitActivity(
        context: Context,
        requestCode: Int,
        intent: Intent,
    ): PendingIntent {
        require(intent.component != null) { "A full-screen or explicit activity intent must set a component." }
        return PendingIntent.getActivity(context, requestCode, intent, IMMUTABLE_UPDATE)
    }

    /** Starts a foreground-capable service, used by the timer's own transport controls. */
    public fun service(
        context: Context,
        requestCode: Int,
        intent: Intent,
    ): PendingIntent {
        require(intent.component != null || intent.`package` != null) {
            "Notification action services must be explicit; set a component or a package."
        }
        return PendingIntent.getForegroundService(context, requestCode, intent, IMMUTABLE_UPDATE)
    }
}
