package dev.studyflow.core.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A to-do item, with zero or more [Reminder]s attached.
 *
 * @property dueAt the *local* wall-clock time the task is due. Deliberately not an [Instant]:
 *   "revise at 09:00" means 09:00 where the user is, and pinning it to an absolute instant makes it
 *   drift by an hour every time DST flips or the user flies somewhere. [dueAtUtc] is the absolute
 *   instant that local time resolves to, which is what scheduling, sorting and the list queries use.
 * @property timeZone the zone [dueAt] is interpreted in, retained so the wall-clock meaning
 *   survives travel and daylight-saving changes.
 * @property isAllDay true when only the date matters. All-day tasks are anchored to local midnight
 *   so "due Friday" does not become Thursday for a user five hours west of the author.
 * @property recurrence how the task repeats; `null` means it happens once. The rule lives on the
 *   task rather than on a reminder because every reminder of a repeating task repeats with it.
 * @property subtasks an ordered checklist. Order is the list order; there is no separate rank
 *   field to keep consistent with it.
 * @property materialId optional link to the material this task is about (for example the chapter
 *   to revise), and [sessionId] to the study session it was planned from or completed in.
 * @property updatedAt last local modification, used by sync to order writes.
 * @property deleted soft-delete marker. Rows are tombstoned rather than removed so a deletion can
 *   be replicated instead of silently resurrected by the next sync.
 */
@Suppress("LongParameterList")
public data class StudyTask(
    val id: String,
    val title: String,
    val notes: String? = null,
    val subjectId: String? = null,
    val materialId: String? = null,
    val sessionId: String? = null,
    val dueAt: LocalDateTime? = null,
    val timeZone: TimeZone = TimeZone.UTC,
    val isAllDay: Boolean = false,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val tags: Set<String> = emptySet(),
    val subtasks: List<Subtask> = emptyList(),
    val recurrence: RecurrenceRule? = null,
    val completedAt: Instant? = null,
    // Reminders are identified by id, not by position: persistence returns them in id order, so a
    // caller must not read meaning into where a reminder sits in this list.
    val reminders: List<Reminder> = emptyList(),
    val updatedAt: Instant,
    val deleted: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "StudyTask.id must not be blank" }
        require(title.isNotBlank()) { "StudyTask.title must not be blank" }
        require(tags.all(String::isNotBlank)) { "StudyTask.tags must not contain blank tags" }
        require(subtasks.distinctBy(Subtask::id).size == subtasks.size) { "subtask ids must be unique" }
        require(reminders.distinctBy(Reminder::id).size == reminders.size) { "reminder ids must be unique" }
        require(reminders.all { it.taskId == id }) { "every reminder must belong to task $id" }
        require(!isAllDay || dueAt != null) { "an all-day task must have a due date" }
        require(!isAllDay || dueAt?.time == LocalTime(0, 0)) {
            "an all-day task must be due at local midnight, was ${dueAt?.time}"
        }
        require(dueAt != null || reminders.none { it.trigger is ReminderTrigger.BeforeDue }) {
            "a reminder relative to the due time needs a due time to fire relative to"
        }
        require(dueAt != null || recurrence == null) { "a recurring task needs a due time to repeat from" }
    }

    /** True when the user has ticked this off. */
    public val isCompleted: Boolean get() = completedAt != null

    /**
     * The absolute instant [dueAt] resolves to, or `null` for a task with no due date.
     *
     * Persisted alongside the local time rather than recomputed per row: every list screen orders
     * and filters by it, and an index cannot be built on a value the database has to call back into
     * Kotlin to compute.
     */
    public val dueAtUtc: Instant? get() = dueAt?.toInstant(timeZone)

    /** True when the task is due, still open and its due instant has passed. */
    public fun isOverdueAt(now: Instant): Boolean = !isCompleted && !deleted && (dueAtUtc?.let { it < now } == true)
}

/** How much the user cares, used for ordering within a day and for notification importance. */
public enum class TaskPriority { NONE, LOW, NORMAL, HIGH }

/** One item of a task's checklist. */
public data class Subtask(
    val id: String,
    val title: String,
    val completedAt: Instant? = null,
) {
    init {
        require(id.isNotBlank()) { "Subtask.id must not be blank" }
        require(title.isNotBlank()) { "Subtask.title must not be blank" }
    }

    /** True when the user has ticked this item off. */
    public val isCompleted: Boolean get() = completedAt != null
}

/**
 * When and how insistently to nudge the user about a [StudyTask].
 *
 * A task may carry several of these — "the evening before" *and* "ten minutes before" is a normal
 * thing to want — so a reminder names the task it belongs to rather than being an optional field
 * on it.
 *
 * @property trigger what the reminder fires relative to; see [ReminderTrigger].
 * @property precision how much the user cares that this lands on the second — which decides what
 *   Android scheduling primitive is used, and how loudly the app should complain if the OS refuses.
 * @property snooze set while the user has postponed this reminder; `null` once it is dismissed or
 *   has never been snoozed.
 * @property lastFiredAt when this reminder was last delivered, so a one-shot reminder is not
 *   re-delivered after a reboot or a reschedule.
 * @property schedulingId the platform registration this reminder currently owns (alarm request id
 *   or work name), so it can be cancelled without re-deriving how it was scheduled.
 */
public data class Reminder(
    val id: String,
    val taskId: String,
    val trigger: ReminderTrigger = ReminderTrigger.BeforeDue(),
    val precision: ReminderPrecision = ReminderPrecision.GENTLE,
    val snooze: SnoozeState? = null,
    val lastFiredAt: Instant? = null,
    val schedulingId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Reminder.id must not be blank" }
        require(taskId.isNotBlank()) { "Reminder.taskId must not be blank" }
    }

    /**
     * Whether this reminder should take over the screen or merely post a notification.
     *
     * Derived from [precision] rather than stored next to it: two fields that can disagree about
     * the same decision is how a reminder ends up claiming to be an alarm while being scheduled
     * inexactly.
     */
    public val mode: ReminderMode
        get() = if (precision == ReminderPrecision.ALARM) ReminderMode.ALARM else ReminderMode.NOTIFICATION
}

/** How a fired [Reminder] presents itself to the user. */
public enum class ReminderMode { NOTIFICATION, ALARM }

/**
 * How precisely a reminder needs to fire.
 *
 * There is no single correct Android API for "remind me later", and pretending otherwise is how
 * apps end up either missing reminders or burning battery. Each level maps to a different
 * primitive, and each carries a different platform cost and permission requirement.
 */
public enum class ReminderPrecision {
    /**
     * "Some time around then." Batched with other system work, may slide by minutes during Doze.
     * Costs nothing, needs no special permission, and is the right default for most study nudges.
     */
    GENTLE,

    /**
     * "At that minute." Wakes the device out of Doze. Requires the exact-alarm permission on
     * Android 12+, which the user can revoke at any time.
     */
    EXACT,

    /**
     * "Wake me up." Treated by the system as a user-visible alarm clock, may show a full-screen
     * intent. The highest-priority and most scrutinised option; reserved for things the user
     * explicitly set as an alarm.
     */
    ALARM,
}

/**
 * What a [Reminder] fires relative to.
 *
 * The two cases the UI offers — "remind me 10 minutes before" and "ring at exactly 07:00" — are
 * genuinely different anchors, not one anchor with a special case. A lead time follows the task
 * when its due date moves or recurs; an absolute time does not.
 */
public sealed interface ReminderTrigger {
    /**
     * Fire [leadTime] before the task's due time; [Duration.ZERO] means "at the due time". Follows
     * every occurrence of a recurring task.
     */
    public data class BeforeDue(
        val leadTime: Duration = Duration.ZERO,
    ) : ReminderTrigger {
        init {
            require(!leadTime.isNegative()) { "ReminderTrigger.BeforeDue.leadTime must not be negative" }
        }
    }

    /**
     * Fire at [instant], whatever the task's due time is.
     *
     * @property timeZone the zone the user picked the time in, retained so the wall-clock intent
     *   ("07:00") can still be shown, and re-resolved, after travel or a DST change.
     */
    public data class AtInstant(
        val instant: Instant,
        val timeZone: TimeZone = TimeZone.UTC,
    ) : ReminderTrigger
}

/**
 * A postponed reminder.
 *
 * @property until when it should fire again.
 * @property count how many times in a row the user has snoozed it, so the UI can stop offering an
 * infinite snooze for something evidently ignored.
 */
public data class SnoozeState(
    val until: Instant,
    val count: Int = 1,
) {
    init {
        require(count >= 1) { "SnoozeState.count must be at least 1, was $count" }
    }
}

/**
 * An intentionally small subset of the iCalendar RRULE grammar — enough for study schedules,
 * small enough to reason about and test exhaustively.
 *
 * Only the *next* occurrence is ever materialised. Expanding a recurrence into a table of future
 * rows looks convenient until the user edits the rule, changes timezone, or the DST boundary moves
 * every row by an hour.
 *
 * @property interval every N periods; `2` with [RecurrenceFrequency.WEEKLY] means fortnightly.
 * @property daysOfWeek for [RecurrenceFrequency.WEEKLY]; empty means "same weekday as the start".
 *   For a monthly or yearly rule it names the single weekday [weekOfMonth] counts.
 * @property dayOfMonth for [RecurrenceFrequency.MONTHLY] and [RecurrenceFrequency.YEARLY]; `null`
 *   means "same day as the start". Values past the end of a short month clamp to that month's last
 *   day rather than skipping it.
 * @property weekOfMonth which occurrence of [daysOfWeek] within the month — `2` with Tuesday is
 *   "every 2nd Tuesday", [LAST_WEEK_OF_MONTH] is "the last Tuesday". Counting a weekday and naming
 *   a date are mutually exclusive ways of picking a day, so this and [dayOfMonth] cannot both be
 *   set.
 * @property monthOfYear for [RecurrenceFrequency.YEARLY]; `null` means "same month as the start".
 * @property exceptions occurrence dates the user has removed from the series. Kept on the rule
 *   because they are part of what the series *is*: without them a re-computed next occurrence would
 *   resurrect an occurrence the user has already dismissed.
 */
@Suppress("LongParameterList")
public data class RecurrenceRule(
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val dayOfMonth: Int? = null,
    val weekOfMonth: Int? = null,
    val monthOfYear: Int? = null,
    val exceptions: Set<LocalDate> = emptySet(),
    val end: RecurrenceEnd = RecurrenceEnd.Never,
) {
    init {
        require(interval >= 1) { "RecurrenceRule.interval must be at least 1, was $interval" }
        require(dayOfMonth == null || dayOfMonth in 1..MAX_DAY_OF_MONTH) {
            "RecurrenceRule.dayOfMonth must be in 1..$MAX_DAY_OF_MONTH, was $dayOfMonth"
        }
        require(weekOfMonth == null || weekOfMonth == LAST_WEEK_OF_MONTH || weekOfMonth in 1..WEEKS_PER_MONTH) {
            "RecurrenceRule.weekOfMonth must be in 1..$WEEKS_PER_MONTH or $LAST_WEEK_OF_MONTH, was $weekOfMonth"
        }
        require(monthOfYear == null || monthOfYear in 1..MONTHS_PER_YEAR) {
            "RecurrenceRule.monthOfYear must be in 1..$MONTHS_PER_YEAR, was $monthOfYear"
        }
        require(frequency == RecurrenceFrequency.WEEKLY || daysOfWeek.isEmpty() || weekOfMonth != null) {
            "daysOfWeek applies to a WEEKLY recurrence, or to a MONTHLY/YEARLY one with a weekOfMonth"
        }
        require(frequency != RecurrenceFrequency.DAILY || (dayOfMonth == null && weekOfMonth == null)) {
            "a DAILY recurrence cannot pick a day of the month"
        }
        require(frequency == RecurrenceFrequency.YEARLY || monthOfYear == null) {
            "monthOfYear only applies to a YEARLY recurrence"
        }
        require(frequency != RecurrenceFrequency.WEEKLY || (dayOfMonth == null && weekOfMonth == null)) {
            "a WEEKLY recurrence repeats by weekday, not by day of the month"
        }
        require(weekOfMonth == null || daysOfWeek.size == 1) {
            "weekOfMonth counts exactly one weekday, but daysOfWeek held ${daysOfWeek.size}"
        }
        require(weekOfMonth == null || dayOfMonth == null) {
            "a rule picks its day either by number or by counted weekday, not both"
        }
    }

    public companion object {
        /** [weekOfMonth] value meaning "the last such weekday of the month". */
        public const val LAST_WEEK_OF_MONTH: Int = -1

        private const val MAX_DAY_OF_MONTH = 31
        private const val WEEKS_PER_MONTH = 5
        private const val MONTHS_PER_YEAR = 12
    }
}

/** The repeat period of a [RecurrenceRule]. */
public enum class RecurrenceFrequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/** When a [RecurrenceRule] stops producing occurrences. */
public sealed interface RecurrenceEnd {
    /** Repeat forever. */
    public data object Never : RecurrenceEnd

    /** Stop after [count] occurrences in total, including the first one. */
    public data class AfterOccurrences(
        val count: Int,
    ) : RecurrenceEnd {
        init {
            require(count >= 1) { "RecurrenceEnd.AfterOccurrences.count must be at least 1, was $count" }
        }
    }

    /** Stop once the occurrence would fall after [date] (inclusive of [date] itself). */
    public data class OnDate(
        val date: LocalDate,
    ) : RecurrenceEnd
}
