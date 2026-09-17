package dev.studyflow.core.domain.tasks

import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.StudyTask
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * The observable task list, in the shapes the screens actually ask for.
 *
 * "Overdue", "today" and "upcoming" are repository operations rather than filters applied to one
 * big list: the alternative is loading every task the user has ever created into memory to find the
 * three that are due this morning, and no index can help with that.
 *
 * The windows are resolved from the injected clock and time zone when a flow is collected, so a
 * caller that must survive midnight or a flight re-collects rather than trusting a cached boundary.
 *
 * Each query is a separate member because each one is a separate index; collapsing them into one
 * parameterised read would hand the caller a filter that SQLite cannot plan.
 */
@Suppress("TooManyFunctions")
public interface TaskRepository {
    /** Every task that has not been deleted, open ones first, in due order. */
    public fun observeTasks(): Flow<List<StudyTask>>

    /** Open tasks whose due instant has already passed. */
    public fun observeOverdue(): Flow<List<StudyTask>>

    /** Open tasks due between now and the end of the user's local day. */
    public fun observeToday(): Flow<List<StudyTask>>

    /** Open tasks due after the end of the user's local day. */
    public fun observeUpcoming(): Flow<List<StudyTask>>

    public fun observeBySubject(subjectId: String): Flow<List<StudyTask>>

    public fun observeByTag(tag: String): Flow<List<StudyTask>>

    public fun observeTask(id: String): Flow<StudyTask?>

    /** Inserts or replaces the whole aggregate — task, reminders, tags and checklist. */
    public suspend fun save(task: StudyTask)

    /**
     * Tombstones a task instead of removing it, so the deletion can be replicated rather than
     * undone by the next sync.
     */
    public suspend fun delete(
        id: String,
        deletedAt: Instant,
    )

    /**
     * Records the reminder state the platform owns — what it was scheduled as, when it last fired
     * and whether the user snoozed it — without rewriting the task around it.
     */
    public suspend fun updateReminder(reminder: Reminder)

    /** Reminders whose trigger has passed, used to catch up after a reboot or a missed alarm. */
    public suspend fun remindersDueBy(now: Instant): List<Reminder>
}
