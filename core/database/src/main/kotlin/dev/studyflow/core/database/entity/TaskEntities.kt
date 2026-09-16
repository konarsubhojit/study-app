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
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

@Entity(
    tableName = "study_tasks",
    foreignKeys = [
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subject_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["subject_id", "due_at", "id"], name = "index_study_tasks_subject_id_due_at"),
        Index(value = ["completed_at", "due_at", "id"], name = "index_study_tasks_completed_at_due_at"),
    ],
)
public data class StudyTaskEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val notes: String?,
    @ColumnInfo(name = "subject_id")
    val subjectId: String?,
    @ColumnInfo(name = "due_at")
    val dueAt: LocalDateTime?,
    @ColumnInfo(name = "time_zone")
    val timeZone: TimeZone,
    @ColumnInfo(name = "completed_at")
    val completedAt: Instant?,
)

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
    indices = [Index(value = ["task_id"], unique = true, name = "index_reminders_task_id")],
)
@Suppress("LongParameterList")
public data class ReminderEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "lead_time")
    val leadTime: Duration,
    val precision: ReminderPrecision,
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
)

public enum class RecurrenceEndType {
    NEVER,
    AFTER_OCCURRENCES,
    ON_DATE,
}

public data class TaskWithReminder(
    @Embedded
    val task: StudyTaskEntity,
    @Relation(parentColumn = "id", entityColumn = "task_id")
    val reminder: ReminderEntity?,
)
