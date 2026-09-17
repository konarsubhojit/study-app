package dev.studyflow.core.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat

/** A notification action button: an explicit, immutable [PendingIntent] behind a label. */
public data class NotificationAction(
    val title: String,
    @param:DrawableRes val icon: Int,
    val intent: PendingIntent,
)

/** How much of a background job is done, in the three shapes a progress bar can take. */
public sealed interface NotificationProgress {
    /** Work has started but its size is not known yet — a hashing pass, a handshake. */
    public data object Indeterminate : NotificationProgress

    public data class Determinate(
        val completed: Int,
        val total: Int,
    ) : NotificationProgress {
        init {
            require(total > 0) { "total must be positive" }
            require(completed in 0..total) { "completed must be within 0..$total" }
        }
    }

    /** The bar is gone; the notification now only carries the outcome. */
    public data object Finished : NotificationProgress
}

/**
 * Builders for the shapes of notification StudyFlow posts (issue #24).
 *
 * The factory takes a [StudyFlowNotificationChannel] rather than a channel id, so a notification
 * cannot be posted to an undocumented channel, and it applies the per-channel conventions
 * (silence, category, alert-once) centrally rather than leaving four call sites to remember them.
 *
 * Timestamps are parameters, never read from a clock here: `SystemClock` and
 * `System.currentTimeMillis` are banned outside `:core:common`, and a chronometer whose base is
 * injected is a chronometer that can be asserted on in a test.
 */
public class StudyFlowNotificationFactory(
    private val context: Context,
    @param:DrawableRes private val smallIcon: Int,
) {
    /**
     * The running-session notification: a chronometer that ticks without the app being alive.
     *
     * The system renders the elapsed (or remaining) time from [startedAtEpochMillis], so nothing
     * here posts an update once a second — which is the same reason the timer's own state is
     * derived from an anchor rather than counted (docs/adr/0003).
     */
    public fun ongoingChronometer(
        title: String,
        text: String,
        startedAtEpochMillis: Long,
        contentIntent: PendingIntent?,
        actions: List<NotificationAction> = emptyList(),
        countDown: Boolean = false,
        channel: StudyFlowNotificationChannel = StudyFlowNotificationChannel.STUDY_TIMER,
    ): Notification =
        builder(channel)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setWhen(startedAtEpochMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(countDown)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .withActions(actions)
            .build()

    /**
     * A background job the user can watch: upload, download, import.
     *
     * Stays ongoing and dismissal-proof while it runs, and becomes an ordinary dismissible
     * notification once [NotificationProgress.Finished] arrives, so a finished upload does not sit
     * in the shade forever.
     */
    public fun progress(
        title: String,
        text: String,
        progress: NotificationProgress,
        contentIntent: PendingIntent?,
        actions: List<NotificationAction> = emptyList(),
        channel: StudyFlowNotificationChannel = StudyFlowNotificationChannel.UPLOADS,
    ): Notification {
        val running = progress != NotificationProgress.Finished
        return builder(channel)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                when (progress) {
                    NotificationProgress.Indeterminate -> setProgress(0, 0, true)
                    is NotificationProgress.Determinate -> setProgress(progress.total, progress.completed, false)
                    NotificationProgress.Finished -> setProgress(0, 0, false)
                }
            }.withActions(actions)
            .build()
    }

    /**
     * A reminder or an alarm: something the user asked to be told at a moment in time.
     *
     * [fullScreenIntent] is only accepted on [StudyFlowNotificationChannel.ALARMS]. Android 14
     * restricts full-screen intents to alarm and calling apps, and a notification that asks for the
     * whole screen on any other channel is both a policy risk and a rude surprise.
     */
    public fun alert(
        channel: StudyFlowNotificationChannel,
        title: String,
        text: String,
        contentIntent: PendingIntent?,
        actions: List<NotificationAction> = emptyList(),
        fullScreenIntent: PendingIntent? = null,
        whenEpochMillis: Long? = null,
    ): Notification {
        require(fullScreenIntent == null || channel.usesFullScreenIntent) {
            "Full-screen intents are only allowed on ${StudyFlowNotificationChannel.ALARMS.id}"
        }
        return builder(channel)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(
                if (channel.usesFullScreenIntent) {
                    NotificationCompat.CATEGORY_ALARM
                } else {
                    NotificationCompat.CATEGORY_REMINDER
                },
            ).apply {
                whenEpochMillis?.let {
                    setWhen(it)
                    setShowWhen(true)
                }
                fullScreenIntent?.let { setFullScreenIntent(it, true) }
            }.withActions(actions)
            .build()
    }

    private fun builder(channel: StudyFlowNotificationChannel): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, channel.id)
            .setSmallIcon(smallIcon)
            .setSilent(channel.isSilentByDefault)
            // Everything StudyFlow posts is about the user's own study data; on a locked screen it
            // shows that there is a notification without spelling out what the task is called.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    private fun NotificationCompat.Builder.withActions(actions: List<NotificationAction>): NotificationCompat.Builder =
        apply {
            actions.forEach { action ->
                addAction(
                    NotificationCompat.Action
                        .Builder(action.icon, action.title, action.intent)
                        .build(),
                )
            }
        }
}
