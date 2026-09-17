package dev.studyflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.SessionEventType
import kotlin.time.Duration
import kotlin.time.Instant

@Entity(
    tableName = "study_sessions",
    foreignKeys = [
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subject_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["subject_id"], name = "index_study_sessions_subject_id")],
)
public data class StudySessionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "subject_id")
    val subjectId: String?,
    val note: String?,
)

@Entity(
    tableName = "session_events",
    foreignKeys = [
        ForeignKey(
            entity = StudySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["session_id", "sequence"],
            unique = true,
            name = "index_session_events_session_id_sequence",
        ),
        Index(value = ["wall_clock"], name = "index_session_events_wall_clock"),
    ],
)
public data class SessionEventEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    val type: SessionEventType,
    val uptime: Duration,
    @ColumnInfo(name = "wall_clock")
    val wallClock: Instant,
    @ColumnInfo(name = "boot_id")
    val bootId: BootId,
    val sequence: Long,
)

public data class SessionWithEvents(
    @Embedded
    val session: StudySessionEntity,
    @Relation(parentColumn = "id", entityColumn = "session_id")
    val events: List<SessionEventEntity>,
)
