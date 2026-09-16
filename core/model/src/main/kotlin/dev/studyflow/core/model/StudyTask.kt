package dev.studyflow.core.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * A to-do item, optionally with a [Reminder].
 *
 * @property dueAt the *local* wall-clock time the task is due. Deliberately not an [Instant]:
 *   "revise at 09:00" means 09:00 where the user is, and pinning it to an absolute instant makes it
 *   drift by an hour every time DST flips or the user flies somewhere.
 * @property timeZone the zone [dueAt] is interpreted in.
 */
public data class StudyTask(
    val id: String,
    val title: String,
    val notes: String? = null,
    val subjectId: String? = null,
    val dueAt: LocalDateTime? = null,
    val timeZone: TimeZone = TimeZone.UTC,
    val completedAt: Instant? = null,
    val reminder: Reminder? = null,
) {
    init {
        require(id.isNotBlank()) { "StudyTask.id must not be blank" }
        require(title.isNotBlank()) { "StudyTask.title must not be blank" }
        require(reminder == null || dueAt != null) { "a reminder needs a due time to fire relative to" }
    }

    /** True when the user has ticked this off. */
    public val isCompleted: Boolean get() = completedAt != null
}

/**
 * When and how insistently to nudge the user about a [StudyTask].
 *
 * @property leadTime how far *before* the task's due time to fire. Zero means "at the due time".
 * @property precision how much the user cares that this lands on the second — which decides what
 *   Android scheduling primitive is used, and how loudly the app should complain if the OS refuses.
 * @property recurrence optional repeat rule; `null` means fire once.
 */
public data class Reminder(
    val id: String,
    val leadTime: kotlin.time.Duration = kotlin.time.Duration.ZERO,
    val precision: ReminderPrecision = ReminderPrecision.GENTLE,
    val recurrence: RecurrenceRule? = null,
) {
    init {
        require(id.isNotBlank()) { "Reminder.id must not be blank" }
        require(!leadTime.isNegative()) { "Reminder.leadTime must not be negative, was $leadTime" }
    }
}

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
 * An intentionally small subset of the iCalendar RRULE grammar — enough for study schedules,
 * small enough to reason about and test exhaustively.
 *
 * Only the *next* occurrence is ever materialised. Expanding a recurrence into a table of future
 * rows looks convenient until the user edits the rule, changes timezone, or the DST boundary moves
 * every row by an hour.
 *
 * @property interval every N periods; `2` with [RecurrenceFrequency.WEEKLY] means fortnightly.
 * @property daysOfWeek for [RecurrenceFrequency.WEEKLY]; empty means "same weekday as the start".
 * @property dayOfMonth for [RecurrenceFrequency.MONTHLY]; `null` means "same day as the start".
 *   Values past the end of a short month clamp to that month's last day rather than skipping it.
 */
public data class RecurrenceRule(
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val dayOfMonth: Int? = null,
    val end: RecurrenceEnd = RecurrenceEnd.Never,
) {
    init {
        require(interval >= 1) { "RecurrenceRule.interval must be at least 1, was $interval" }
        require(dayOfMonth == null || dayOfMonth in 1..MAX_DAY_OF_MONTH) {
            "RecurrenceRule.dayOfMonth must be in 1..$MAX_DAY_OF_MONTH, was $dayOfMonth"
        }
        require(frequency == RecurrenceFrequency.WEEKLY || daysOfWeek.isEmpty()) {
            "daysOfWeek only applies to a WEEKLY recurrence"
        }
        require(frequency == RecurrenceFrequency.MONTHLY || dayOfMonth == null) {
            "dayOfMonth only applies to a MONTHLY recurrence"
        }
    }

    private companion object {
        const val MAX_DAY_OF_MONTH = 31
    }
}

/** The repeat period of a [RecurrenceRule]. */
public enum class RecurrenceFrequency { DAILY, WEEKLY, MONTHLY }

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
