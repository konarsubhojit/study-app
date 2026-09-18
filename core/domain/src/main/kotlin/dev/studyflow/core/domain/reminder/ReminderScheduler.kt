package dev.studyflow.core.domain.reminder

import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.ReminderPrecision
import dev.studyflow.core.model.ReminderTrigger
import dev.studyflow.core.model.StudyTask
import kotlin.time.Instant

/**
 * Chooses *how* a reminder should be delivered, given what the OS is currently willing to allow.
 *
 * ### Why this is a matrix and not one API call
 *
 * Android has spent a decade making it progressively harder to wake a device at a precise moment,
 * for good battery reasons. There is no single primitive that is both reliable and cheap:
 *
 * - `WorkManager` / inexact alarms are free and battery-friendly, but may slide by many minutes
 *   while the device is dozing — fine for "revise chapter 4 this evening", useless for an exam
 *   alarm.
 * - `setExactAndAllowWhileIdle` fires to the minute, but on Android 12+ needs a permission the user
 *   can revoke at any time, and Play scrutinises the use case.
 * - `setAlarmClock` is the only thing the system treats as sacrosanct, will show in the status bar
 *   and can carry a full-screen intent — appropriate only for reminders the user explicitly set as
 *   an alarm.
 *
 * Picking the strongest primitive for everything drains the battery and risks a policy rejection;
 * picking the weakest means missed exams. So the decision is explicit, data-driven, and — crucially
 * — reports what it could *not* honour, so the UI can tell the user instead of silently failing.
 */
public object ReminderScheduler {
    /**
     * Plans the next delivery for every reminder on [task].
     *
     * @param now used both to skip past occurrences and to decide whether a reminder is overdue.
     * @return one plan per reminder that still has something to fire; empty when the task is
     *   completed, deleted, carries no reminders, or every recurrence has run out.
     */
    public fun plan(
        task: StudyTask,
        capabilities: SchedulingCapabilities,
        now: Instant,
    ): List<ReminderPlan> = task.reminders.mapNotNull { plan(task, it, capabilities, now) }

    /**
     * Plans the next delivery for one reminder of [task].
     *
     * @return the plan, or `null` when there is nothing left to schedule (task completed or
     *   deleted, recurrence exhausted, or a one-shot absolute reminder that has already fired).
     */
    public fun plan(
        task: StudyTask,
        reminder: Reminder,
        capabilities: SchedulingCapabilities,
        now: Instant,
    ): ReminderPlan? {
        if (task.isCompleted || task.deleted) return null
        val anchor = anchorFor(task, reminder, now) ?: return null

        // A snooze postpones this delivery without rewriting what the reminder is anchored to, so
        // the next occurrence of a repeating task is unaffected by the user hitting snooze once.
        val snoozedUntil = reminder.snooze?.until?.takeIf { it > now }

        val plan =
            ReminderPlan(
                reminderId = reminder.id,
                taskId = task.id,
                occurrenceAt = anchor.occurrenceAt,
                triggerAt = snoozedUntil ?: anchor.triggerAt,
                delivery = deliveryFor(reminder, capabilities),
                degradations = degradationsFor(reminder, capabilities),
            )
        return plan.takeUnless { reminder.lastFiredAt?.let { firedAt -> firedAt >= it.triggerAt } == true }
    }

    /**
     * Resolves what the reminder fires against.
     *
     * The two triggers are different anchors rather than one anchor with an exception: a lead time
     * follows the task through every recurrence, while an absolute time is a one-shot the task's
     * due date cannot move.
     */
    private fun anchorFor(
        task: StudyTask,
        reminder: Reminder,
        now: Instant,
    ): ReminderAnchor? =
        when (val trigger = reminder.trigger) {
            is ReminderTrigger.BeforeDue -> {
                val dueAt = task.dueAt ?: return null
                val occurrence =
                    RecurrenceCalculator.nextOccurrence(
                        rule = task.recurrence,
                        start = dueAt,
                        zone = task.timeZone,
                        // Search by *occurrence*, not by trigger time. A reminder whose lead time
                        // has already elapsed is late, not irrelevant — the user still needs
                        // telling that the thing is due shortly — so it keeps a trigger in the past
                        // and fires immediately.
                        after = now,
                    ) ?: return null
                ReminderAnchor(occurrenceAt = occurrence, triggerAt = occurrence - trigger.leadTime)
            }

            is ReminderTrigger.AtInstant -> {
                // Already delivered means delivered: re-planning after a reboot or a permission
                // change must not ring an absolute alarm a second time.
                if (reminder.lastFiredAt?.let { it >= trigger.instant } == true) {
                    null
                } else {
                    ReminderAnchor(
                        occurrenceAt = task.dueAtUtc ?: trigger.instant,
                        triggerAt = trigger.instant,
                    )
                }
            }
        }

    private data class ReminderAnchor(
        val occurrenceAt: Instant,
        val triggerAt: Instant,
    )

    /**
     * Plans every outstanding reminder — the operation run on boot, on app update, and after a time
     * or timezone change, all of which silently drop previously registered alarms.
     */
    public fun planAll(
        tasks: List<StudyTask>,
        capabilities: SchedulingCapabilities,
        now: Instant,
    ): List<ReminderPlan> = tasks.flatMap { plan(it, capabilities, now) }

    private fun deliveryFor(
        reminder: Reminder,
        capabilities: SchedulingCapabilities,
    ): ReminderDelivery =
        when (reminder.precision) {
            ReminderPrecision.GENTLE -> {
                ReminderDelivery.INEXACT
            }

            ReminderPrecision.EXACT -> {
                if (capabilities.canScheduleExactAlarms) ReminderDelivery.EXACT else ReminderDelivery.INEXACT
            }

            ReminderPrecision.ALARM -> {
                when {
                    // An alarm-clock alarm needs no separate exact-alarm permission, which is precisely
                    // why it is reserved for reminders the user explicitly framed as an alarm.
                    capabilities.canUseAlarmClock -> ReminderDelivery.ALARM_CLOCK

                    capabilities.canScheduleExactAlarms -> ReminderDelivery.EXACT

                    else -> ReminderDelivery.INEXACT
                }
            }
        }

    private fun degradationsFor(
        reminder: Reminder,
        capabilities: SchedulingCapabilities,
    ): Set<ReminderDegradation> =
        buildSet {
            val timeCritical = reminder.precision != ReminderPrecision.GENTLE
            val alarmStyle = reminder.precision == ReminderPrecision.ALARM

            if (!capabilities.notificationsEnabled) {
                add(ReminderDegradation.NOTIFICATIONS_DENIED)
            }
            if (timeCritical && !canFirePrecisely(reminder, capabilities)) {
                add(ReminderDegradation.EXACT_ALARMS_DENIED)
            }
            if (alarmStyle && !capabilities.canUseFullScreenIntent) {
                add(ReminderDegradation.FULL_SCREEN_INTENT_DENIED)
            }
            if (timeCritical && capabilities.batteryOptimised) {
                add(ReminderDegradation.BATTERY_OPTIMISED)
            }
        }

    /**
     * Whether the chosen precision can still be honoured to the minute.
     *
     * An alarm-style reminder has a second route to precision — `setAlarmClock` needs no
     * exact-alarm permission — so losing that permission only degrades it if the alarm-clock route
     * is unavailable too.
     */
    private fun canFirePrecisely(
        reminder: Reminder,
        capabilities: SchedulingCapabilities,
    ): Boolean =
        when (reminder.precision) {
            ReminderPrecision.GENTLE -> true
            ReminderPrecision.EXACT -> capabilities.canScheduleExactAlarms
            ReminderPrecision.ALARM -> capabilities.canUseAlarmClock || capabilities.canScheduleExactAlarms
        }
}

/**
 * What the platform will currently let the app do.
 *
 * Every one of these can change without the app running — the user revokes a permission in
 * Settings, or the OEM's battery manager decides the app is "unused" — so this is read fresh at
 * planning time rather than cached.
 */
public data class SchedulingCapabilities(
    /** `AlarmManager.canScheduleExactAlarms()` on Android 12+. */
    val canScheduleExactAlarms: Boolean = true,
    /** `POST_NOTIFICATIONS` granted on Android 13+, and the channel not blocked. */
    val notificationsEnabled: Boolean = true,
    /** `setAlarmClock()` is usable; it does not require the exact-alarm permission. */
    val canUseAlarmClock: Boolean = true,
    /** `USE_FULL_SCREEN_INTENT` granted — restricted to alarm/call apps on Android 14+. */
    val canUseFullScreenIntent: Boolean = true,
    /** The app is subject to battery optimisation, so delivery may be delayed further. */
    val batteryOptimised: Boolean = false,
)

/** The Android primitive a reminder should be handed to. */
public enum class ReminderDelivery {
    /** `WorkManager` or an inexact alarm. Cheap, batched, may slide during Doze. */
    INEXACT,

    /** `AlarmManager.setExactAndAllowWhileIdle`. To the minute, needs the exact-alarm permission. */
    EXACT,

    /** `AlarmManager.setAlarmClock`, optionally with a full-screen intent. Highest priority. */
    ALARM_CLOCK,
}

/** A promise the app could not keep, to be surfaced in the UI rather than swallowed. */
public enum class ReminderDegradation {
    /** No notification will be visible at all until the user grants the permission. */
    NOTIFICATIONS_DENIED,

    /** The reminder was downgraded to inexact and may arrive late. */
    EXACT_ALARMS_DENIED,

    /** An alarm-style reminder cannot take over the screen; it will only post a notification. */
    FULL_SCREEN_INTENT_DENIED,

    /** Aggressive battery management may delay delivery regardless of the primitive used. */
    BATTERY_OPTIMISED,
}

/**
 * A concrete instruction for the platform layer: fire this reminder, at this instant, this way.
 *
 * @property occurrenceAt when the task itself is due.
 * @property triggerAt when the notification should appear — [occurrenceAt] minus the lead time.
 *   May be in the past when the app was not running at the right moment (device off, app force
 *   stopped, permission only just granted); the platform layer should then fire straight away.
 * @property degradations everything the app promised the user but cannot currently deliver.
 */
public data class ReminderPlan(
    val reminderId: String,
    val taskId: String,
    val occurrenceAt: Instant,
    val triggerAt: Instant,
    val delivery: ReminderDelivery,
    val degradations: Set<ReminderDegradation> = emptySet(),
) {
    /** True when the user should be told something is wrong with this reminder. */
    public val isDegraded: Boolean get() = degradations.isNotEmpty()

    /** True when the trigger moment has already passed and the reminder should fire now. */
    public fun isOverdueAt(now: Instant): Boolean = triggerAt <= now
}
