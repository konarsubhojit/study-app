package dev.studyflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import dev.studyflow.core.domain.session.SessionCorrectionType
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.SessionEventType
import dev.studyflow.core.model.SessionStatus
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
    indices = [
        Index(value = ["subject_id"], name = "index_study_sessions_subject_id"),
        // Serves the active-session lookup, which runs on every timer command and on every
        // subscription to the running session.
        Index(value = ["device_id", "status", "deleted"], name = "index_study_sessions_device_id_status_deleted"),
    ],
)
public data class StudySessionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "subject_id")
    val subjectId: String?,
    val note: String?,
    val status: SessionStatus,
    @ColumnInfo(name = "started_at")
    val startedAt: Instant,
    @ColumnInfo(name = "ended_at")
    val endedAt: Instant?,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
    val deleted: Boolean = false,
    /**
     * True once a [dev.studyflow.core.domain.session.SessionHistoryEditor] correction has set this
     * row's timing directly, rather than [dev.studyflow.core.domain.session.SessionReducer] deriving
     * it by replaying `session_events`. See [overrideCountedMillis] for why this needs its own
     * elapsed columns, and `StudySession.manualOverride` for the full rationale.
     */
    @ColumnInfo(name = "manual_override")
    val manualOverride: Boolean = false,
    /**
     * The corrected [dev.studyflow.core.model.SessionElapsed.counted], read instead of the event
     * log when [manualOverride] is set.
     *
     * Ordinarily elapsed time is never stored — it is always re-derived from `session_events` so a
     * missed write can never leave a stale number behind (see [SessionWithEvents]). A correction
     * breaks that assumption on purpose: an edited, split, merged or manually entered session's
     * duration is a human decision with no log to replay, so it has to live somewhere, and this
     * column is the narrow, documented exception.
     */
    @ColumnInfo(name = "override_counted_millis")
    val overrideCountedMillis: Long? = null,
    /** The corrected [dev.studyflow.core.model.SessionElapsed.unverified]; see [overrideCountedMillis]. */
    @ColumnInfo(name = "override_unverified_millis")
    val overrideUnverifiedMillis: Long? = null,
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

/**
 * One append-only audit row: a session's state, before and after one field of a
 * [dev.studyflow.core.domain.session.SessionCorrection].
 *
 * Every field here is a plain column rather than a serialized blob, matching how every other
 * entity in this schema stores its data — see `CONTRIBUTING.md`'s note that Room's converters are
 * for stable scalar pairs, not ad-hoc object graphs. `before_*` columns are all nullable together:
 * a row that did not exist before the correction (split's second half, a manual entry) has no
 * "before" state to record, and undoing it re-tombstones the row instead.
 *
 * Rows are never updated or deleted: undo appends a new row with
 * [SessionCorrectionEntity.type] `RESTORE` rather than touching the row it reverses.
 */
@Entity(
    tableName = "session_corrections",
    foreignKeys = [
        ForeignKey(
            entity = StudySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["session_id", "at"], name = "index_session_corrections_session_id_at"),
        Index(value = ["correction_group_id"], name = "index_session_corrections_correction_group_id"),
    ],
)
@Suppress("LongParameterList")
public data class SessionCorrectionEntity(
    @PrimaryKey
    val id: String,
    /** Shared by every row one [dev.studyflow.core.domain.session.SessionCorrection] produced. */
    @ColumnInfo(name = "correction_group_id")
    val correctionGroupId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    val type: SessionCorrectionType,
    val at: Instant,
    /** For a `RESTORE` row, the [correctionGroupId] of the correction it undoes. */
    @ColumnInfo(name = "restores_correction_group_id")
    val restoresCorrectionGroupId: String?,
    @ColumnInfo(name = "before_subject_id")
    val beforeSubjectId: String?,
    @ColumnInfo(name = "before_note")
    val beforeNote: String?,
    @ColumnInfo(name = "before_started_at")
    val beforeStartedAt: Instant?,
    @ColumnInfo(name = "before_ended_at")
    val beforeEndedAt: Instant?,
    @ColumnInfo(name = "before_status")
    val beforeStatus: SessionStatus?,
    @ColumnInfo(name = "before_counted_millis")
    val beforeCountedMillis: Long?,
    @ColumnInfo(name = "before_unverified_millis")
    val beforeUnverifiedMillis: Long?,
    @ColumnInfo(name = "before_deleted")
    val beforeDeleted: Boolean?,
    @ColumnInfo(name = "before_manual_override")
    val beforeManualOverride: Boolean?,
    @ColumnInfo(name = "before_updated_at")
    val beforeUpdatedAt: Instant?,
    @ColumnInfo(name = "after_subject_id")
    val afterSubjectId: String?,
    @ColumnInfo(name = "after_note")
    val afterNote: String?,
    @ColumnInfo(name = "after_started_at")
    val afterStartedAt: Instant,
    @ColumnInfo(name = "after_ended_at")
    val afterEndedAt: Instant?,
    @ColumnInfo(name = "after_status")
    val afterStatus: SessionStatus,
    @ColumnInfo(name = "after_counted_millis")
    val afterCountedMillis: Long,
    @ColumnInfo(name = "after_unverified_millis")
    val afterUnverifiedMillis: Long,
    @ColumnInfo(name = "after_deleted")
    val afterDeleted: Boolean,
    @ColumnInfo(name = "after_manual_override")
    val afterManualOverride: Boolean,
    @ColumnInfo(name = "after_updated_at")
    val afterUpdatedAt: Instant,
)
