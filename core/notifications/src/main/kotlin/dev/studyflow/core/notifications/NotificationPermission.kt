package dev.studyflow.core.notifications

/**
 * Why the app is about to talk about notifications.
 *
 * The permission is asked for at a moment the user can connect it to something they just did, and
 * never at cold start: [APP_LAUNCH] exists precisely so that "should I ask now?" has an answer that
 * is checked by a test rather than by remembering not to call the launcher from `onCreate`.
 *
 * Each moment also names what the user loses if they say no, so a denied state is a sentence on
 * screen rather than a feature that quietly stops working.
 */
public enum class NotificationMoment(
    public val channel: StudyFlowNotificationChannel?,
    public val prompts: Boolean,
    public val rationaleKey: NotificationMessageKey,
    public val degradationKey: NotificationMessageKey,
) {
    /** Cold start. Never prompts — there is nothing on screen yet for the permission to be about. */
    APP_LAUNCH(
        channel = null,
        prompts = false,
        rationaleKey = NotificationMessageKey.RATIONALE_GENERIC,
        degradationKey = NotificationMessageKey.DEGRADED_GENERIC,
    ),

    STARTING_TIMER(
        channel = StudyFlowNotificationChannel.STUDY_TIMER,
        prompts = true,
        rationaleKey = NotificationMessageKey.RATIONALE_TIMER,
        degradationKey = NotificationMessageKey.DEGRADED_TIMER,
    ),

    SAVING_REMINDER(
        channel = StudyFlowNotificationChannel.TASK_REMINDERS,
        prompts = true,
        rationaleKey = NotificationMessageKey.RATIONALE_REMINDER,
        degradationKey = NotificationMessageKey.DEGRADED_REMINDER,
    ),

    SAVING_ALARM(
        channel = StudyFlowNotificationChannel.ALARMS,
        prompts = true,
        rationaleKey = NotificationMessageKey.RATIONALE_ALARM,
        degradationKey = NotificationMessageKey.DEGRADED_ALARM,
    ),

    STARTING_UPLOAD(
        channel = StudyFlowNotificationChannel.UPLOADS,
        prompts = true,
        rationaleKey = NotificationMessageKey.RATIONALE_UPLOAD,
        degradationKey = NotificationMessageKey.DEGRADED_UPLOAD,
    ),

    /** The user opened the notification settings screen, so they are already thinking about this. */
    NOTIFICATION_SETTINGS(
        channel = null,
        prompts = true,
        rationaleKey = NotificationMessageKey.RATIONALE_GENERIC,
        degradationKey = NotificationMessageKey.DEGRADED_GENERIC,
    ),
}

/**
 * Stable keys for the copy shown around the permission.
 *
 * Text is resolved by the UI layer, following the same rule as the error models: a core module
 * names the message, it does not choose the words or the locale.
 */
public enum class NotificationMessageKey {
    RATIONALE_GENERIC,
    RATIONALE_TIMER,
    RATIONALE_REMINDER,
    RATIONALE_ALARM,
    RATIONALE_UPLOAD,
    DEGRADED_GENERIC,
    DEGRADED_TIMER,
    DEGRADED_REMINDER,
    DEGRADED_ALARM,
    DEGRADED_UPLOAD,
}

/** Where `POST_NOTIFICATIONS` currently stands for this install. */
public enum class NotificationPermissionStatus {
    /** Below Android 13: the permission does not exist and is implicitly held. */
    NOT_REQUIRED,
    GRANTED,

    /** Never asked, so the system dialog is still available. */
    NOT_REQUESTED,

    /** Denied once; Android still shows the dialog, but only after a rationale. */
    DENIED,

    /** Denied for good: the system dialog is a no-op and only settings can grant it now. */
    PERMANENTLY_DENIED,
}

/**
 * The permission as the app sees it, which is more than the permission grant alone.
 *
 * [notificationsEnabled] is the app-level switch in system settings. It can be off while the
 * permission is granted (and is always the deciding factor below Android 13), so the two are kept
 * apart rather than collapsed into one boolean that would be wrong on one of the two paths.
 */
public data class NotificationPermissionState(
    val status: NotificationPermissionStatus,
    val notificationsEnabled: Boolean,
) {
    /** True when a notification posted now would actually reach the user. */
    val canPost: Boolean
        get() =
            notificationsEnabled &&
                (
                    status == NotificationPermissionStatus.GRANTED ||
                        status == NotificationPermissionStatus.NOT_REQUIRED
                )
}

/** What the UI should do next about notifications at a given [NotificationMoment]. */
public sealed interface NotificationPermissionAction {
    /** Nothing to do: either permission is in place, or this moment is not one for asking. */
    public data object None : NotificationPermissionAction

    /** Show the system permission dialog straight away — the user has not seen it yet. */
    public data object RequestPermission : NotificationPermissionAction

    /** Explain first, because Android has already recorded one refusal. */
    public data class ShowRationale(
        val messageKey: NotificationMessageKey,
    ) : NotificationPermissionAction

    /**
     * Only system settings can fix this: the permission is permanently denied, or notifications
     * are switched off app-wide. [degradationKey] is what the user loses until they do.
     */
    public data class OpenSystemSettings(
        val degradationKey: NotificationMessageKey,
        val channel: StudyFlowNotificationChannel?,
    ) : NotificationPermissionAction
}

/**
 * The one place that decides whether to ask for `POST_NOTIFICATIONS`.
 *
 * Pure data in, pure data out: the timer, the reminder editor and the settings screen all get the
 * same answer, and the awkward cases (permanently denied, granted-but-switched-off, pre-Android 13
 * with notifications disabled) are covered by unit tests instead of by whoever wrote that screen.
 */
public object NotificationPermissionPolicy {
    public fun actionFor(
        state: NotificationPermissionState,
        moment: NotificationMoment,
    ): NotificationPermissionAction {
        if (state.canPost) return NotificationPermissionAction.None
        if (!moment.prompts) return NotificationPermissionAction.None

        return when {
            // Granted or not needed, yet nothing can be posted: the app-level switch is off, and
            // only the user can turn it back on.
            state.status == NotificationPermissionStatus.GRANTED ||
                state.status == NotificationPermissionStatus.NOT_REQUIRED -> {
                openSettings(moment)
            }

            state.status == NotificationPermissionStatus.NOT_REQUESTED -> {
                NotificationPermissionAction.RequestPermission
            }

            state.status == NotificationPermissionStatus.DENIED -> {
                NotificationPermissionAction.ShowRationale(moment.rationaleKey)
            }

            else -> {
                openSettings(moment)
            }
        }
    }

    /**
     * What to tell the user when the answer is still "no" after [actionFor] ran its course.
     *
     * Returns `null` when nothing is degraded, so a caller can render this unconditionally.
     */
    public fun degradationFor(
        state: NotificationPermissionState,
        moment: NotificationMoment,
    ): NotificationMessageKey? = if (state.canPost) null else moment.degradationKey

    private fun openSettings(moment: NotificationMoment): NotificationPermissionAction.OpenSystemSettings =
        NotificationPermissionAction.OpenSystemSettings(
            degradationKey = moment.degradationKey,
            channel = moment.channel,
        )
}
