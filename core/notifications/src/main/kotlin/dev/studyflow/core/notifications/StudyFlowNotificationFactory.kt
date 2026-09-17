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

/**
 * The optional, rarely-set parts of [StudyFlowNotificationFactory.alert], grouped so the function
 * itself does not grow one parameter per feature (issue #47 added color/publicVersion/group at
 * once, on top of the pre-existing [whenEpochMillis]).
 *
 * @param whenEpochMillis shown as the notification's timestamp when set; read from a clock at the
 *   call site, never here — see the class-level note on why.
 * @param color tints the small icon and, on some launchers, the notification header — the
 *   subject's colour when the task has one. Left `null` falls back to the system default.
 * @param publicVersion shown instead of the full content on a locked screen when the *system*
 *   is configured to hide sensitive notifications there. Every notification this factory builds
 *   already carries [NotificationCompat.VISIBILITY_PRIVATE], which only hides content when that
 *   system setting is on; supplying this is what gives a lock screen something to show ("You have
 *   a reminder") rather than nothing at all.
 * @param group ties this notification to others posted with the same key so the system can
 *   collapse them under one summary instead of one heads-up alert each — see
 *   [StudyFlowNotificationFactory.groupedReminderSummary].
 */
public data class AlertPresentation(
    val whenEpochMillis: Long? = null,
    val color: Int? = null,
    val publicVersion: Notification? = null,
    val group: String? = null,
)

/** The platform-owned timer rendering for an ongoing notification. */
public data class ChronometerPresentation(
    val countDown: Boolean = false,
    val usesChronometer: Boolean = true,
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
        chronometer: ChronometerPresentation = ChronometerPresentation(),
        channel: StudyFlowNotificationChannel = StudyFlowNotificationChannel.STUDY_TIMER,
    ): Notification =
        builder(channel)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setWhen(startedAtEpochMillis)
            .setShowWhen(true)
            .setUsesChronometer(chronometer.usesChronometer)
            .setChronometerCountDown(chronometer.countDown)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
     *
     * @param presentation the optional colour/lock-screen/grouping treatment — see
     *   [AlertPresentation] for what each part does and why they are bundled together.
     */
    public fun alert(
        channel: StudyFlowNotificationChannel,
        title: String,
        text: String,
        contentIntent: PendingIntent?,
        actions: List<NotificationAction> = emptyList(),
        fullScreenIntent: PendingIntent? = null,
        presentation: AlertPresentation = AlertPresentation(),
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
            .setOnlyAlertOnce(true)
            .setCategory(
                if (channel.usesFullScreenIntent) {
                    NotificationCompat.CATEGORY_ALARM
                } else {
                    NotificationCompat.CATEGORY_REMINDER
                },
            ).apply {
                presentation.whenEpochMillis?.let {
                    setWhen(it)
                    setShowWhen(true)
                }
                fullScreenIntent?.let { setFullScreenIntent(it, true) }
                presentation.color?.let {
                    setColor(it)
                    setColorized(false)
                }
                presentation.publicVersion?.let(::setPublicVersion)
                presentation.group?.let(::setGroup)
            }.withActions(actions)
            .build()
    }

    /**
     * The one notification that stands in for several grouped reminders in the shade.
     *
     * Posting ten individual reminders with the same [group] is not enough on its own: Android only
     * collapses a group once a summary exists, so without this ten due tasks still show as ten
     * heads-up alerts on API levels (or launchers) that do not auto-group. [lines] renders as an
     * [NotificationCompat.InboxStyle] list of task titles, capped by the style itself at five lines.
     *
     * @param publicVersion see [AlertPresentation.publicVersion] — a summary listing task titles
     *   is exactly the sensitive content a locked screen must not leak on its own.
     */
    public fun groupedReminderSummary(
        title: String,
        text: String,
        lines: List<String>,
        contentIntent: PendingIntent?,
        group: String,
        publicVersion: Notification? = null,
        channel: StudyFlowNotificationChannel = StudyFlowNotificationChannel.TASK_REMINDERS,
    ): Notification =
        builder(channel)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                lines
                    .fold(NotificationCompat.InboxStyle().setBigContentTitle(title)) { style, line ->
                        style.addLine(line)
                    },
            ).setContentIntent(contentIntent)
            .setGroup(group)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .apply { publicVersion?.let(::setPublicVersion) }
            .build()

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
