package dev.studyflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.studyflow.core.database.entity.ReminderEntity
import dev.studyflow.core.database.entity.StudyTaskEntity
import dev.studyflow.core.database.entity.SubtaskEntity
import dev.studyflow.core.database.entity.TaskTagEntity
import dev.studyflow.core.database.entity.TaskWithReminders
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Reads and writes the task aggregate.
 *
 * The list reads filter on `deleted = 0` and order by `due_at_utc, id`, which is the shape the
 * `index_study_tasks_open_due_at_utc` covering index exists for; `DatabaseQueryPlanTest` asserts
 * the plans stay that way as the queries evolve.
 */
@Dao
@Suppress("TooManyFunctions")
public abstract class StudyTaskDao {
    @Transaction
    @Query(
        """
        SELECT * FROM study_tasks
        WHERE deleted = 0
        ORDER BY completed_at IS NOT NULL ASC, due_at_utc IS NULL ASC, due_at_utc ASC, id ASC
        """,
    )
    public abstract fun observeAll(): Flow<List<TaskWithReminders>>

    /** Open tasks whose due instant has already passed. */
    @Transaction
    @Query(
        """
        SELECT * FROM study_tasks
        WHERE deleted = 0 AND completed_at IS NULL AND due_at_utc < :now
        ORDER BY due_at_utc ASC, id ASC
        """,
    )
    public abstract fun observeOverdue(now: Instant): Flow<List<TaskWithReminders>>

    /**
     * Open tasks due within a half-open window, which is how "today" is expressed: the caller
     * resolves the local day boundaries to instants, so the window follows the user's zone rather
     * than being baked into SQL.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM study_tasks
        WHERE deleted = 0 AND completed_at IS NULL AND due_at_utc >= :from AND due_at_utc < :until
        ORDER BY due_at_utc ASC, id ASC
        """,
    )
    public abstract fun observeDueBetween(
        from: Instant,
        until: Instant,
    ): Flow<List<TaskWithReminders>>

    /** Open tasks due at or after [from]; "upcoming" passes the end of today. */
    @Transaction
    @Query(
        """
        SELECT * FROM study_tasks
        WHERE deleted = 0 AND completed_at IS NULL AND due_at_utc >= :from
        ORDER BY due_at_utc ASC, id ASC
        """,
    )
    public abstract fun observeDueFrom(from: Instant): Flow<List<TaskWithReminders>>

    @Transaction
    @Query(
        """
        SELECT * FROM study_tasks
        WHERE subject_id = :subjectId AND deleted = 0
        ORDER BY due_at_utc IS NULL ASC, due_at_utc ASC, id ASC
        """,
    )
    public abstract fun observeBySubject(subjectId: String): Flow<List<TaskWithReminders>>

    @Transaction
    @Query(
        """
        SELECT study_tasks.* FROM study_tasks
        JOIN task_tags ON task_tags.task_id = study_tasks.id
        WHERE task_tags.tag = :tag AND study_tasks.deleted = 0
        ORDER BY study_tasks.due_at_utc IS NULL ASC, study_tasks.due_at_utc ASC, study_tasks.id ASC
        """,
    )
    public abstract fun observeByTag(tag: String): Flow<List<TaskWithReminders>>

    @Transaction
    @Query("SELECT * FROM study_tasks WHERE id = :id")
    public abstract fun observeById(id: String): Flow<TaskWithReminders?>

    /** Reminders that should already have fired, ordered so the oldest is delivered first. */
    @Query(
        """
        SELECT * FROM reminders
        WHERE trigger_at_utc IS NOT NULL AND trigger_at_utc <= :now
        ORDER BY trigger_at_utc ASC, id ASC
        """,
    )
    public abstract suspend fun remindersDueBy(now: Instant): List<ReminderEntity>

    /**
     * Replaces the whole aggregate.
     *
     * Children are deleted and re-inserted rather than diffed: a task has a handful of tags and
     * subtasks, and one transactional replace cannot leave a half-updated checklist behind.
     */
    @Transaction
    public open suspend fun save(value: TaskWithReminders) {
        upsertTask(value.task)
        deleteRemindersForTask(value.task.id)
        deleteTagsForTask(value.task.id)
        deleteSubtasksForTask(value.task.id)
        insertReminders(value.reminders)
        insertTags(value.tags)
        insertSubtasks(value.subtasks)
    }

    /**
     * Replaces several aggregates in one transaction.
     *
     * Tasks that are split out of a recurring series only make sense as a pair, so either both
     * halves land or neither does.
     */
    @Transaction
    public open suspend fun saveAll(values: List<TaskWithReminders>) {
        values.forEach { save(it) }
    }

    /** Tombstones a task, keeping the row so the deletion can be synced. */
    @Query("UPDATE study_tasks SET deleted = 1, updated_at = :deletedAt WHERE id = :id")
    public abstract suspend fun softDelete(
        id: String,
        deletedAt: Instant,
    )

    @Upsert
    public abstract suspend fun upsertTasks(tasks: List<StudyTaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract suspend fun insertReminders(reminders: List<ReminderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract suspend fun insertTags(tags: List<TaskTagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract suspend fun insertSubtasks(subtasks: List<SubtaskEntity>)

    /**
     * Updates only the columns the platform owns.
     *
     * Writing the whole row instead would mean recomputing `trigger_at_utc`, which is derived from
     * the task's due instant — state a snooze or a delivery has no business touching.
     */
    @Query(
        """
        UPDATE reminders
        SET snooze_until = :snoozeUntil, snooze_count = :snoozeCount,
            last_fired_at = :lastFiredAt, scheduling_id = :schedulingId
        WHERE id = :id
        """,
    )
    public abstract suspend fun updateReminderState(
        id: String,
        snoozeUntil: Instant?,
        snoozeCount: Int?,
        lastFiredAt: Instant?,
        schedulingId: String?,
    )

    @Query("SELECT COUNT(*) FROM study_tasks")
    public abstract suspend fun count(): Int

    @Upsert
    protected abstract suspend fun upsertTask(task: StudyTaskEntity)

    @Query("DELETE FROM reminders WHERE task_id = :taskId")
    protected abstract suspend fun deleteRemindersForTask(taskId: String)

    @Query("DELETE FROM task_tags WHERE task_id = :taskId")
    protected abstract suspend fun deleteTagsForTask(taskId: String)

    @Query("DELETE FROM task_subtasks WHERE task_id = :taskId")
    protected abstract suspend fun deleteSubtasksForTask(taskId: String)
}
