package dev.studyflow.core.notifications

import androidx.core.app.NotificationManagerCompat

/**
 * The drawers the user sees in system settings.
 *
 * Grouping exists so that "turn off everything about uploads" is one switch rather than a hunt
 * through a flat list, and so a channel added later lands in an existing, already-understood group.
 */
public enum class StudyFlowChannelGroup(
    public val id: String,
    public val groupName: String,
) {
    /** Anything that reports on a study session the user started. */
    FOCUS("studyflow.group.focus", "Focus"),

    /** Anything the user asked to be told about at a point in time. */
    REMINDERS("studyflow.group.reminders", "Reminders"),

    /** Work the app does on the user's behalf without being watched. */
    BACKGROUND("studyflow.group.background", "Background work"),
}

/**
 * Every notification StudyFlow can post, with its channel and default importance (issue #24).
 *
 * The enum *is* the documentation the acceptance criteria asks for: a notification that does not
 * name one of these cannot be built, because [StudyFlowNotificationFactory] takes this type rather
 * than a channel id string. Importance is the app's opening offer only — once a channel exists the
 * user owns it, which is why [NotificationChannelRegistrar] never rewrites an existing channel.
 *
 * The four channels differ in what interrupting the user is worth:
 *
 * | Channel | Importance | Why |
 * |---|---|---|
 * | [STUDY_TIMER] | low | an ongoing chronometer the user opted into; it must be visible, never noisy |
 * | [FOCUS_INTERVALS] | default | a scheduled focus/break transition that should get attention |
 * | [TASK_REMINDERS] | default | the user asked to be told; a heads-up peek is proportionate |
 * | [ALARMS] | high | "wake me for the exam" — full-screen, vibrating, allowed to be loud |
 * | [UPLOADS] | min | progress the user can watch if they care, silent and badge-free if they do not |
 */
public enum class StudyFlowNotificationChannel(
    public val id: String,
    public val group: StudyFlowChannelGroup,
    public val channelName: String,
    public val description: String,
    public val importance: Int,
    public val showsBadge: Boolean = true,
    public val vibrates: Boolean = false,
) {
    STUDY_TIMER(
        id = "studyflow.channel.study_timer",
        group = StudyFlowChannelGroup.FOCUS,
        channelName = "Study timer",
        description = "The running session, so the timer stays visible after you leave the app.",
        importance = NotificationManagerCompat.IMPORTANCE_LOW,
        showsBadge = false,
    ),

    FOCUS_INTERVALS(
        id = "studyflow.channel.focus_intervals",
        group = StudyFlowChannelGroup.FOCUS,
        channelName = "Focus intervals",
        description = "Pomodoro focus and break transition prompts.",
        importance = NotificationManagerCompat.IMPORTANCE_DEFAULT,
        vibrates = true,
    ),

    TASK_REMINDERS(
        id = "studyflow.channel.task_reminders",
        group = StudyFlowChannelGroup.REMINDERS,
        channelName = "Task reminders",
        description = "Reminders for the tasks and revision you scheduled.",
        importance = NotificationManagerCompat.IMPORTANCE_DEFAULT,
        vibrates = true,
    ),

    ALARMS(
        id = "studyflow.channel.alarms",
        group = StudyFlowChannelGroup.REMINDERS,
        channelName = "Alarms",
        description = "Time-critical alarms you set, such as an exam start time.",
        importance = NotificationManagerCompat.IMPORTANCE_HIGH,
        vibrates = true,
    ),

    UPLOADS(
        id = "studyflow.channel.uploads",
        group = StudyFlowChannelGroup.BACKGROUND,
        channelName = "Uploads",
        description = "Progress for material uploads running in the background.",
        importance = NotificationManagerCompat.IMPORTANCE_MIN,
        showsBadge = false,
    ),

    ;

    /** Alarms are the only channel allowed to take over the screen (Android 14 restricts the rest). */
    public val usesFullScreenIntent: Boolean
        get() = this == ALARMS

    /** Progress and ongoing state are updated often; a sound on every update would be punishment. */
    public val isSilentByDefault: Boolean
        get() = importance <= NotificationManagerCompat.IMPORTANCE_LOW

    public companion object {
        /** Resolves a system channel id back to its declaration, or `null` for a foreign channel. */
        public fun fromId(channelId: String?): StudyFlowNotificationChannel? =
            entries.firstOrNull { it.id == channelId }
    }
}
