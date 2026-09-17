package dev.studyflow.core.domain.tasks

import dev.studyflow.core.domain.reminder.RecurrenceCalculator
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.ReminderTrigger
import dev.studyflow.core.model.StudyTask
import kotlin.time.Instant

/**
 * The lifecycle of a repeating task: one materialised occurrence at a time.
 *
 * ### Why a task *is* its next occurrence
 *
 * A recurring task is stored as a single row whose `dueAt` is the occurrence currently outstanding.
 * The alternative — writing a row per occurrence — has to decide how far ahead to expand, and every
 * row it wrote is wrong the moment the user edits the rule, travels, or the clocks change. Keeping
 * the head on the task means the rule stays the only stored truth, and the reminder pipeline needs
 * no concept of recurrence at all: it schedules the one due time it can see.
 *
 * ### Why an occurrence is consumed, never jumped over
 *
 * Every transition moves the head on by exactly one occurrence. Ticking Monday's task off on
 * Wednesday moves it to Tuesday, not to Thursday — the missed occurrence is the user's to see and
 * dismiss, and a series that quietly re-synchronises with "now" is a series that has dropped
 * something. Equally, nothing re-materialises an occurrence that has already been consumed, so a
 * reminder cannot fire twice for the same one.
 */
public object TaskSeries {
    /**
     * Consumes the occurrence currently at the head of [task].
     *
     * Completing an occurrence and skipping it are the same operation on the series — the user's
     * verdict on the occurrence does not change what happens next — so both go through here.
     *
     * A task with no rule is a series of one: advancing past its only occurrence closes it. The
     * same is true of the final occurrence of a bounded rule, because a repeating task with nothing
     * left to repeat is finished, not perpetually outstanding.
     *
     * @param at when the user acted; becomes the completion and modification time.
     */
    public fun advance(
        task: StudyTask,
        at: Instant,
    ): StudyTask = advancedOrNull(task, at) ?: task.copy(completedAt = at, updatedAt = at)

    /**
     * Removes the occurrence at the head of [task] from the series without consuming a rule
     * occurrence anywhere else.
     *
     * @return the series with the next occurrence at its head, or `null` when there is none left.
     */
    private fun advancedOrNull(
        task: StudyTask,
        at: Instant,
    ): StudyTask? {
        val dueAt = task.dueAt ?: return null
        val advanced = RecurrenceCalculator.advance(task.recurrence, dueAt) ?: return null
        return task.copy(
            dueAt = advanced.start,
            recurrence = advanced.rule,
            completedAt = null,
            reminders = task.reminders.map(::rearm),
            updatedAt = at,
        )
    }

    /**
     * Applies [edit] to [task] with the scope the user picked.
     *
     * @param at when the user saved the edit.
     * @param newId supplies ids for the rows a detached occurrence needs; every reminder and
     *   checklist item is a row of its own, so one call per row.
     * @param edit the user's changes, applied to the occurrence or to the series depending on
     *   [scope].
     */
    public fun edit(
        task: StudyTask,
        scope: RecurrenceEditScope,
        at: Instant,
        newId: () -> String,
        edit: (StudyTask) -> StudyTask,
    ): SeriesEdit {
        // "This and future" needs no split: the head *is* the first of the future occurrences, and
        // the occurrences before it were consumed rather than stored, so there is no past half of
        // the series left to preserve.
        if (scope == RecurrenceEditScope.THIS_AND_FUTURE || task.recurrence == null) {
            return SeriesEdit(occurrence = edit(task).copy(updatedAt = at), series = null)
        }
        val detached = edit(task.detached(newId)).copy(updatedAt = at)
        return SeriesEdit(occurrence = detached, series = advancedOrNull(task, at))
    }

    /**
     * A standalone copy of the occurrence at the head of [this], with no rule of its own.
     *
     * Every child row is re-identified: reminder and checklist ids are unique across the whole
     * database, so a copy that kept them would overwrite the rows of the series it was detached
     * from. The platform registration is dropped for the same reason — the copy owns no alarm yet.
     */
    private fun StudyTask.detached(newId: () -> String): StudyTask {
        val id = newId()
        return copy(
            id = id,
            recurrence = null,
            completedAt = null,
            subtasks = subtasks.map { it.copy(id = newId()) },
            reminders =
                reminders.map {
                    rearm(it).copy(id = newId(), taskId = id, schedulingId = null)
                },
        )
    }

    /**
     * Clears the delivery state a consumed occurrence left behind.
     *
     * A snooze is a decision about one occurrence ("not now, in ten minutes"), so carrying it into
     * the next one would silently postpone a reminder the user never touched. An absolute trigger
     * is a one-shot that belongs to no occurrence, so its fired marker survives.
     */
    private fun rearm(reminder: Reminder): Reminder =
        reminder.copy(
            snooze = null,
            lastFiredAt = reminder.lastFiredAt.takeIf { reminder.trigger is ReminderTrigger.AtInstant },
        )
}

/** Which part of a series an edit applies to. */
public enum class RecurrenceEditScope {
    /** Only the occurrence in front of the user; the series carries on unchanged without it. */
    THIS_OCCURRENCE,

    /** This occurrence and every one after it — the series itself. */
    THIS_AND_FUTURE,
}

/**
 * The result of editing a recurring task.
 *
 * @property occurrence the task carrying the user's changes — the detached single occurrence for
 *   [RecurrenceEditScope.THIS_OCCURRENCE], otherwise the series itself.
 * @property series the remaining untouched series, present only when the edit detached one
 *   occurrence from it and it still has occurrences left.
 */
public data class SeriesEdit(
    val occurrence: StudyTask,
    val series: StudyTask?,
) {
    /** Everything that has to be saved, in the order it should be written. */
    public val tasks: List<StudyTask> get() = listOfNotNull(occurrence, series)
}
