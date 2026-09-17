package dev.studyflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.ReminderPrecision
import dev.studyflow.core.model.TaskPriority
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A task row.
 *
 * @property dueAt the user's local wall-clock due time, kept verbatim together with [timeZone] so
 *   persistence never changes what "09:00" meant.
 * @property dueAtUtc the instant [dueAt] resolves to, derived on write. Every list screen filters
 *   and orders by it, and SQLite cannot index a value it would have to call back into Kotlin for.
 */
@Entity(
    tableName = "study_tasks",
    foreignKeys = [
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subject_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = MaterialEntity::class,
            parentColumns = ["id"],
            childColumns = ["material_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = StudySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        // The three list screens — overdue, today, upcoming — are all
        // "open, not deleted, due in this window, in due order", which is exactly this index.
        Index(
            value = ["deleted", "completed_at", "due_at_utc", "id"],
            name = "index_study_tasks_open_due_at_utc",
        ),
        Index(value = ["subject_id", "due_at_utc", "id"], name = "index_study_tasks_subject_id_due_at"),
        Index(value = ["material_id"], name = "index_study_tasks_material_id"),
        Index(value = ["session_id"], name = "index_study_tasks_session_id"),
        // Sync reads "everything changed since", tombstones included.
        Index(value = ["updated_at", "id"], name = "index_study_tasks_updated_at"),
    ],
)
@Suppress("LongParameterList")
public data class StudyTaskEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val notes: String?,
    @ColumnInfo(name = "subject_id")
    val subjectId: String?,
    @ColumnInfo(name = "material_id")
    val materialId: String?,
    @ColumnInfo(name = "session_id")
    val sessionId: String?,
    @ColumnInfo(name = "due_at")
    val dueAt: LocalDateTime?,
    @ColumnInfo(name = "due_at_utc")
    val dueAtUtc: Instant?,
    @ColumnInfo(name = "time_zone")
    val timeZone: TimeZone,
    @ColumnInfo(name = "is_all_day")
    val isAllDay: Boolean,
    val priority: TaskPriority,
    @ColumnInfo(name = "recurrence_frequency")
    val recurrenceFrequency: RecurrenceFrequency?,
    @ColumnInfo(name = "recurrence_interval")
    val recurrenceInterval: Int?,
    @ColumnInfo(name = "recurrence_days_of_week")
    val recurrenceDaysOfWeek: Set<DayOfWeek>?,
    @ColumnInfo(name = "recurrence_day_of_month")
    val recurrenceDayOfMonth: Int?,
    @ColumnInfo(name = "recurrence_end_type")
    val recurrenceEndType: RecurrenceEndType?,
    @ColumnInfo(name = "recurrence_end_count")
    val recurrenceEndCount: Int?,
    @ColumnInfo(name = "recurrence_end_date")
    val recurrenceEndDate: LocalDate?,
    @ColumnInfo(name = "completed_at")
    val completedAt: Instant?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
    val deleted: Boolean,
)

/**
 * A reminder row. Several may point at the same task.
 *
 * @property triggerAtUtc when this reminder next fires, derived on write from the trigger and the
 *   task's due instant, so "what is outstanding" is one indexed scan rather than a full table read
 *   plus Kotlin-side arithmetic. `null` when there is nothing to fire.
 */
@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = StudyTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["task_id"], name = "index_reminders_task_id"),
        Index(value = ["trigger_at_utc", "id"], name = "index_reminders_trigger_at_utc"),
    ],
)
@Suppress("LongParameterList")
public data class ReminderEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "trigger_type")
    val triggerType: ReminderTriggerType,
    @ColumnInfo(name = "lead_time")
    val leadTime: Duration?,
    @ColumnInfo(name = "trigger_instant")
    val triggerInstant: Instant?,
    @ColumnInfo(name = "trigger_time_zone")
    val triggerTimeZone: TimeZone?,
    @ColumnInfo(name = "trigger_at_utc")
    val triggerAtUtc: Instant?,
    val precision: ReminderPrecision,
    @ColumnInfo(name = "snooze_until")
    val snoozeUntil: Instant?,
    @ColumnInfo(name = "snooze_count")
    val snoozeCount: Int?,
    @ColumnInfo(name = "last_fired_at")
    val lastFiredAt: Instant?,
    @ColumnInfo(name = "scheduling_id")
    val schedulingId: String?,
)

/** Which anchor a [ReminderEntity] fires from. */
public enum class ReminderTriggerType {
    BEFORE_DUE,
    AT_INSTANT,
}

/** A tag applied to a task. Normalized so "show me everything tagged exam" is an indexed read. */
@Entity(
    tableName = "task_tags",
    primaryKeys = ["task_id", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = StudyTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tag", "task_id"], name = "index_task_tags_tag")],
)
public data class TaskTagEntity(
    @ColumnInfo(name = "task_id")
    val taskId: String,
    val tag: String,
)

/** One checklist item. [position] is the user's ordering, unique within a task. */
@Entity(
    tableName = "task_subtasks",
    foreignKeys = [
        ForeignKey(
            entity = StudyTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["task_id", "position"], unique = true, name = "index_task_subtasks_task_id_position"),
    ],
)
public data class SubtaskEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "task_id")
    val taskId: String,
    val position: Int,
    val title: String,
    @ColumnInfo(name = "completed_at")
    val completedAt: Instant?,
)

public enum class RecurrenceEndType {
    NEVER,
    AFTER_OCCURRENCES,
    ON_DATE,
}

/** A task and everything that hangs off it, read as one aggregate. */
public data class TaskWithReminders(
    @Embedded
    val task: StudyTaskEntity,
    @Relation(parentColumn = "id", entityColumn = "task_id")
    val reminders: List<ReminderEntity> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "task_id")
    val tags: List<TaskTagEntity> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "task_id")
    val subtasks: List<SubtaskEntity> = emptyList(),
)
