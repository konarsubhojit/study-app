package dev.studyflow.core.domain.session

import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import kotlin.time.Instant

/**
 * What kind of human correction produced a [SessionCorrection].
 *
 * These are audit categories, not [dev.studyflow.core.model.SessionEventType] values: a correction
 * never rewrites the timer's own event log (see the module KDoc on [SessionHistoryEditor]), so it
 * needs its own, separate vocabulary.
 */
public enum class SessionCorrectionType {
    EDIT_TIMING,
    EDIT_SUBJECT,
    EDIT_NOTE,
    SPLIT,
    MERGE,
    MANUAL_ENTRY,
    DELETE,

    /** Reverses the last correction on a session, appended rather than removing what it undoes. */
    RESTORE,
}

/**
 * The fields of a [StudySession] a correction can change, frozen at one instant.
 *
 * Deliberately not the whole [StudySession]: [dev.studyflow.core.model.StudySession.id] and
 * [dev.studyflow.core.model.StudySession.deviceId] never change for an existing row, so repeating
 * them here would just be another place for an audit row to disagree with the session it
 * describes. [manualOverride] *is* included, even though it usually follows from
 * [SessionCorrectionType] mechanically: undoing a correction that was itself applied on top of an
 * earlier override (an edit of an edit) must put the row back into override mode too, and the only
 * reliable way to know that is to have recorded it.
 */
public data class StudySessionSnapshot(
    val subjectId: String?,
    val note: String?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val status: SessionStatus,
    val elapsed: SessionElapsed,
    val deleted: Boolean,
    val manualOverride: Boolean,
    val updatedAt: Instant,
)

/**
 * One session row's before/after state within a single correction.
 *
 * A correction that only touches one session (an edit, a delete) produces exactly one of these. A
 * correction that reshapes the graph of sessions — split creates a row, merge tombstones one or
 * more — produces one per affected row, which is what lets [SessionHistoryEditor] undo the whole
 * operation rather than leaving half of it reversed.
 *
 * @property before the row's state immediately before the correction, or `null` when the row did
 *   not exist yet (split's second half, a manual entry). Undoing such a change re-tombstones the
 *   row instead of resurrecting a "no row" state that the schema cannot represent.
 * @property after the row's state immediately after the correction. Always present: a correction
 *   that touches a row always leaves it in some state, even a deleted one.
 */
public data class SessionSnapshotChange(
    val sessionId: String,
    val before: StudySessionSnapshot?,
    val after: StudySessionSnapshot,
)

/**
 * One append-only audit entry: what changed, on which sessions, and when.
 *
 * This is the log [CONTRIBUTING.md] calls "stays append-only" — nothing here is ever mutated or
 * deleted once written. Undo does not remove or edit the correction it reverses; it appends a new
 * [SessionCorrectionType.RESTORE] correction whose [changes] happen to move each row back to the
 * `before` state the original correction recorded, and which points back at the correction it
 * reverses through [restoresCorrectionId].
 *
 * @property id identifies this correction, and is shared by every [SessionSnapshotChange] row it
 *   produced — that is how a multi-session correction (split, merge, and the undo of either) is
 *   grouped back together for display and for undo.
 * @property restoresCorrectionId for a [SessionCorrectionType.RESTORE], the id of the correction
 *   being undone; `null` otherwise.
 */
public data class SessionCorrection(
    val id: String,
    val type: SessionCorrectionType,
    val at: Instant,
    val changes: List<SessionSnapshotChange>,
    val restoresCorrectionId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "SessionCorrection.id must not be blank" }
        require(changes.isNotEmpty()) { "SessionCorrection.changes must not be empty" }
    }
}

/** Freezes the fields a correction can change. */
public fun StudySession.asSnapshot(): StudySessionSnapshot =
    StudySessionSnapshot(
        subjectId = subjectId,
        note = note,
        startedAt = startedAt,
        endedAt = endedAt,
        status = status,
        elapsed = elapsed,
        deleted = deleted,
        manualOverride = manualOverride,
        updatedAt = updatedAt,
    )

/** Applies a snapshot's fields on top of an existing row, preserving its identity and device. */
public fun StudySession.withSnapshot(snapshot: StudySessionSnapshot): StudySession =
    copy(
        subjectId = snapshot.subjectId,
        note = snapshot.note,
        startedAt = snapshot.startedAt,
        endedAt = snapshot.endedAt,
        status = snapshot.status,
        elapsed = snapshot.elapsed,
        deleted = snapshot.deleted,
        manualOverride = snapshot.manualOverride,
        updatedAt = snapshot.updatedAt,
    )
