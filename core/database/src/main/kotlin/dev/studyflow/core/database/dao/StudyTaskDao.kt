package dev.studyflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.studyflow.core.database.entity.ReminderEntity
import dev.studyflow.core.database.entity.StudyTaskEntity
import dev.studyflow.core.database.entity.TaskWithReminder
import kotlinx.coroutines.flow.Flow

@Dao
public abstract class StudyTaskDao {
    @Transaction
    @Query(
        """
        SELECT * FROM study_tasks
        ORDER BY completed_at IS NOT NULL ASC, due_at IS NULL ASC, due_at ASC, id ASC
        """,
    )
    public abstract fun observeAll(): Flow<List<TaskWithReminder>>

    @Transaction
    public open suspend fun save(value: TaskWithReminder) {
        upsertTask(value.task)
        deleteReminderForTask(value.task.id)
        value.reminder?.let { insertReminder(it) }
    }

    @Upsert
    public abstract suspend fun upsertTasks(tasks: List<StudyTaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract suspend fun insertReminders(reminders: List<ReminderEntity>)

    @Query("SELECT COUNT(*) FROM study_tasks")
    public abstract suspend fun count(): Int

    @Upsert
    protected abstract suspend fun upsertTask(task: StudyTaskEntity)

    @Insert
    protected abstract suspend fun insertReminder(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE task_id = :taskId")
    protected abstract suspend fun deleteReminderForTask(taskId: String)
}
