package dev.studyflow.core.domain.session

import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import kotlin.time.Duration
import kotlin.time.Instant

/** A user-initiated change to session history (issue #32). */
public sealed interface HistoryEditCommand {
    /**
     * Rewrites when a stopped session started and ended.
     *
     * Timing can only be corrected once a session is stopped: a `RUNNING` or `PAUSED` session's
     * duration is still being measured by [dev.studyflow.core.domain.timer.TimerEngine], and
     * overwriting it mid-measurement would race the very log the number comes from. Stop it first
     * (an ordinary [dev.studyflow.core.domain.timer.TimerCommand.Stop]), then correct it.
     */
    public data class EditTiming(
        val sessionId: String,
        val startedAt: Instant,
        val endedAt: Instant,
    ) : HistoryEditCommand

    /** Reassigns a session to a different subject, or clears it. Legal at any status. */
    public data class EditSubject(
        val sessionId: String,
        val subjectId: String?,
    ) : HistoryEditCommand

    /** Replaces a session's free-text note. Legal at any status. */
    public data class EditNote(
        val sessionId: String,
        val note: String?,
    ) : HistoryEditCommand

    /**
     * Divides one stopped session into two at [at].
     *
     * @property newSessionId id for the second half; the original [sessionId] keeps its identity
     *   for the first half, so links from tasks or reminders into the original id stay valid.
     */
    public data class Split(
        val sessionId: String,
        val at: Instant,
        val newSessionId: String,
    ) : HistoryEditCommand

    /**
     * Combines two or more stopped sessions into one.
     *
     * The earliest session (by [StudySession.startedAt]) absorbs the rest and keeps its id; the
     * others are tombstoned with a correction pointing at the merge, exactly like an ordinary
     * delete, so they still replicate correctly and can still be individually inspected in the
     * audit trail.
     */
    public data class Merge(
        val sessionIds: List<String>,
    ) : HistoryEditCommand

    /** Records offline study that was never timed live. Always created already stopped. */
    public data class ManualEntry(
        val sessionId: String,
        val deviceId: String,
        val subjectId: String?,
        val note: String?,
        val startedAt: Instant,
        val endedAt: Instant,
    ) : HistoryEditCommand

    /** Tombstones a session. Reversible: see [Undo]. */
    public data class Delete(
        val sessionId: String,
    ) : HistoryEditCommand

    /** Reverses the most recent correction recorded against a session. */
    public data class Undo(
        val correction: SessionCorrection,
    ) : HistoryEditCommand
}

/** Why a [HistoryEditCommand] was refused. */
public enum class HistoryRejection {
    SESSION_NOT_FOUND,
    SESSION_DELETED,

    /** The session must be stopped before its timing can be corrected — see [HistoryEditCommand.EditTiming]. */
    SESSION_NOT_STOPPED,

    /** `endedAt` was not strictly after `startedAt`. */
    INVALID_TIMING,

    /** The split instant was not strictly between the session's start and end. */
    SPLIT_POINT_OUT_OF_BOUNDS,

    MERGE_REQUIRES_AT_LEAST_TWO_SESSIONS,

    /** Merged sessions must share a subject, so the merged total is not attributed to the wrong one. */
    MERGE_SUBJECT_MISMATCH,

    /** Merged sessions must belong to the same device, the scope timing anchors are comparable within. */
    MERGE_DEVICE_MISMATCH,
    NOTHING_TO_UNDO,
}

/** Outcome of validating a [HistoryEditCommand] against the current rows it touches. */
public sealed interface HistoryEditResult {
    /**
     * The command was legal.
     *
     * @property sessions every row the caller must persist — an edit yields one, split and merge
     *   yield several. Always includes the full new state of the row, never a partial patch.
     */
    public data class Applied(
        val sessions: List<StudySession>,
        val correction: SessionCorrection,
    ) : HistoryEditResult

    public data class Rejected(
        val reason: HistoryRejection,
    ) : HistoryEditResult
}

/**
 * Turns a [HistoryEditCommand] into row updates and an audit entry, as a pure function.
 *
 * ### Why this never touches the event log
 *
 * [dev.studyflow.core.domain.timer.TimerEngine] treats a session's [dev.studyflow.core.model.SessionEvent]
 * log as a measurement: it sums every closed interval and would double-count a `STARTED`/`STOPPED`
 * pair appended after the session already stopped. A correction is not a measurement — it is a
 * human overriding one — so it is recorded in a completely separate, equally append-only place (see
 * [SessionCorrection]) and applied to the [StudySession] projection's own fields
 * ([StudySession.manualOverride]) instead. This is what lets an edit, a split or a manual entry take
 * effect immediately without either mutating the log the timer relies on or inventing a fake replay
 * of it.
 *
 * ### Why corrections deal only in wall-clock duration
 *
 * [SessionElapsed] normally separates `counted` (monotonic-clock) time from `unverified`
 * (wall-clock-only) time, because the device cannot vouch for a gap it cannot measure. A correction
 * is the opposite case: the *human* is vouching for the interval, by construction, so the resulting
 * duration is recorded entirely as `counted` and never as `unverified`.
 */
public object SessionHistoryEditor {
    public fun execute(
        command: HistoryEditCommand,
        sessions: Map<String, StudySession>,
        correctionId: String,
        at: Instant,
    ): HistoryEditResult =
        when (command) {
            is HistoryEditCommand.EditTiming -> editTiming(command, sessions, correctionId, at)
            is HistoryEditCommand.EditSubject -> editSubject(command, sessions, correctionId, at)
            is HistoryEditCommand.EditNote -> editNote(command, sessions, correctionId, at)
            is HistoryEditCommand.Split -> split(command, sessions, correctionId, at)
            is HistoryEditCommand.Merge -> merge(command, sessions, correctionId, at)
            is HistoryEditCommand.ManualEntry -> manualEntry(command, correctionId, at)
            is HistoryEditCommand.Delete -> delete(command, sessions, correctionId, at)
            is HistoryEditCommand.Undo -> undo(command, sessions, correctionId, at)
        }

    // Guard-clause validation rejects one condition at a time, which reads far more clearly here
    // than nesting every check into one boolean expression.
    @Suppress("ReturnCount")
    private fun editTiming(
        command: HistoryEditCommand.EditTiming,
        sessions: Map<String, StudySession>,
        correctionId: String,
        at: Instant,
    ): HistoryEditResult {
        val session =
            sessions[command.sessionId] ?: return HistoryEditResult.Rejected(HistoryRejection.SESSION_NOT_FOUND)
        rejectionForEditableRow(session)?.let { return HistoryEditResult.Rejected(it) }
        if (session.status != SessionStatus.STOPPED) {
            return HistoryEditResult.Rejected(HistoryRejection.SESSION_NOT_STOPPED)
        }
        if (command.endedAt <= command.startedAt) {
            return HistoryEditResult.Rejected(HistoryRejection.INVALID_TIMING)
        }

        val updated =
            session.copy(
                startedAt = command.startedAt,
                endedAt = command.endedAt,
                elapsed = SessionElapsed(counted = command.endedAt - command.startedAt),
                manualOverride = true,
                updatedAt = at,
            )
        return applied(
            SessionCorrectionType.EDIT_TIMING,
            correctionId,
            at,
            listOf(updated),
            listOf(change(session, updated)),
        )
    }

    private fun editSubject(
        command: HistoryEditCommand.EditSubject,
        sessions: Map<String, StudySession>,
        correctionId: String,
        at: Instant,
    ): HistoryEditResult {
        val session =
            sessions[command.sessionId] ?: return HistoryEditResult.Rejected(HistoryRejection.SESSION_NOT_FOUND)
        rejectionForEditableRow(session)?.let { return HistoryEditResult.Rejected(it) }

        val updated = session.copy(subjectId = command.subjectId, updatedAt = at)
        return applied(
            SessionCorrectionType.EDIT_SUBJECT,
            correctionId,
            at,
            listOf(updated),
            listOf(change(session, updated)),
        )
    }

    private fun editNote(
        command: HistoryEditCommand.EditNote,
        sessions: Map<String, StudySession>,
        correctionId: String,
        at: Instant,
    ): HistoryEditResult {
        val session =
            sessions[command.sessionId] ?: return HistoryEditResult.Rejected(HistoryRejection.SESSION_NOT_FOUND)
        rejectionForEditableRow(session)?.let { return HistoryEditResult.Rejected(it) }

        val updated = session.copy(note = command.note, updatedAt = at)
        return applied(
            SessionCorrectionType.EDIT_NOTE,
            correctionId,
            at,
            listOf(updated),
            listOf(change(session, updated)),
        )
    }

    // Same guard-clause shape as editTiming: each rule rejects independently before the split is
    // actually computed.
    @Suppress("ReturnCount")
    private fun split(
        command: HistoryEditCommand.Split,
        sessions: Map<String, StudySession>,
        correctionId: String,
        at: Instant,
    ): HistoryEditResult {
        val session =
            sessions[command.sessionId] ?: return HistoryEditResult.Rejected(HistoryRejection.SESSION_NOT_FOUND)
        rejectionForEditableRow(session)?.let { return HistoryEditResult.Rejected(it) }
        if (session.status != SessionStatus.STOPPED) {
            return HistoryEditResult.Rejected(HistoryRejection.SESSION_NOT_STOPPED)
        }
        val start = session.startedAt
        val end = session.endedAt
        if (end == null || command.at <= start || command.at >= end) {
            return HistoryEditResult.Rejected(HistoryRejection.SPLIT_POINT_OUT_OF_BOUNDS)
        }

        val total = end - start
        val firstShare = (command.at - start) / total
        val (firstElapsed, secondElapsed) = session.elapsed.splitBy(firstShare)

        val first =
            session.copy(
                endedAt = command.at,
                elapsed = firstElapsed,
                manualOverride = true,
                updatedAt = at,
            )
        val second =
            StudySession(
                id = command.newSessionId,
                subjectId = session.subjectId,
                note = session.note,
                startedAt = command.at,
                endedAt = end,
                status = SessionStatus.STOPPED,
                elapsed = secondElapsed,
                deviceId = session.deviceId,
                updatedAt = at,
                manualOverride = true,
            )
        return applied(
            SessionCorrectionType.SPLIT,
            correctionId,
            at,
            listOf(first, second),
            listOf(
                change(session, first),
                SessionSnapshotChange(second.id, before = null, after = second.asSnapshot()),
            ),
        )
    }

    // Same shape as editTiming/split: each merge rule rejects independently.
    @Suppress("ReturnCount")
    private fun merge(
        command: HistoryEditCommand.Merge,
        sessions: Map<String, StudySession>,
        correctionId: String,
        at: Instant,
    ): HistoryEditResult {
        if (command.sessionIds.distinct().size < 2) {
            return HistoryEditResult.Rejected(HistoryRejection.MERGE_REQUIRES_AT_LEAST_TWO_SESSIONS)
        }
        val resolved = mutableListOf<StudySession>()
        for (id in command.sessionIds.distinct()) {
            val session = sessions[id] ?: return HistoryEditResult.Rejected(HistoryRejection.SESSION_NOT_FOUND)
            rejectionForEditableRow(session)?.let { return HistoryEditResult.Rejected(it) }
            if (session.status != SessionStatus.STOPPED) {
                return HistoryEditResult.Rejected(HistoryRejection.SESSION_NOT_STOPPED)
            }
            resolved += session
        }
        if (resolved.map(StudySession::subjectId).distinct().size > 1) {
            return HistoryEditResult.Rejected(HistoryRejection.MERGE_SUBJECT_MISMATCH)
        }
        if (resolved.map(StudySession::deviceId).distinct().size > 1) {
            return HistoryEditResult.Rejected(HistoryRejection.MERGE_DEVICE_MISMATCH)
        }

        val sorted = resolved.sortedWith(compareBy(StudySession::startedAt, StudySession::id))
        val primary = sorted.first()
        val secondaries = sorted.drop(1)
        // Elapsed is summed rather than re-derived from `end - start`, so a merge across a real
        // break in study (a meal, a class) does not silently invent time for the gap.
        val mergedElapsed =
            SessionElapsed(
                counted = resolved.fold(Duration.ZERO) { acc, session -> acc + session.elapsed.counted },
                unverified = resolved.fold(Duration.ZERO) { acc, session -> acc + session.elapsed.unverified },
            )
        val mergedNote =
            resolved
                .mapNotNull { it.note?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()
                .joinToString("\n")
                .ifEmpty { null }

        val merged =
            primary.copy(
                startedAt = sorted.first().startedAt,
                endedAt = sorted.last().endedAt,
                elapsed = mergedElapsed,
                note = mergedNote,
                manualOverride = true,
                updatedAt = at,
            )
        val tombstoned = secondaries.map { it.copy(deleted = true, updatedAt = at) }

        val changes =
            listOf(change(primary, merged)) +
                secondaries.zip(tombstoned) { before, after -> change(before, after) }
        return applied(
            SessionCorrectionType.MERGE,
            correctionId,
            at,
            listOf(merged) + tombstoned,
            changes,
        )
    }

    private fun manualEntry(
        command: HistoryEditCommand.ManualEntry,
        correctionId: String,
        at: Instant,
    ): HistoryEditResult {
        if (command.endedAt <= command.startedAt) {
            return HistoryEditResult.Rejected(HistoryRejection.INVALID_TIMING)
        }

        val session =
            StudySession(
                id = command.sessionId,
                subjectId = command.subjectId,
                note = command.note,
                startedAt = command.startedAt,
                endedAt = command.endedAt,
                status = SessionStatus.STOPPED,
                elapsed = SessionElapsed(counted = command.endedAt - command.startedAt),
                deviceId = command.deviceId,
                updatedAt = at,
                manualOverride = true,
            )
        val change = SessionSnapshotChange(session.id, before = null, after = session.asSnapshot())
        return applied(SessionCorrectionType.MANUAL_ENTRY, correctionId, at, listOf(session), listOf(change))
    }

    private fun delete(
        command: HistoryEditCommand.Delete,
        sessions: Map<String, StudySession>,
        correctionId: String,
        at: Instant,
    ): HistoryEditResult {
        val session =
            sessions[command.sessionId] ?: return HistoryEditResult.Rejected(HistoryRejection.SESSION_NOT_FOUND)
        if (session.deleted) return HistoryEditResult.Rejected(HistoryRejection.SESSION_DELETED)

        val updated = session.copy(deleted = true, updatedAt = at)
        return applied(
            SessionCorrectionType.DELETE,
            correctionId,
            at,
            listOf(updated),
            listOf(change(session, updated)),
        )
    }

    private fun undo(
        command: HistoryEditCommand.Undo,
        sessions: Map<String, StudySession>,
        correctionId: String,
        at: Instant,
    ): HistoryEditResult {
        val original = command.correction
        val restored =
            original.changes.map { affected ->
                val current =
                    sessions[affected.sessionId]
                        ?: return HistoryEditResult.Rejected(HistoryRejection.SESSION_NOT_FOUND)
                // A row that did not exist before the correction is put back into its tombstoned
                // resting state, since the schema always keeps the row rather than truly erasing it.
                val target = affected.before ?: current.asSnapshot().copy(deleted = true, updatedAt = at)
                current.withSnapshot(target.copy(updatedAt = at))
            }
        val changes =
            original.changes.mapIndexed { index, affected ->
                val current = requireNotNull(sessions[affected.sessionId])
                SessionSnapshotChange(
                    sessionId = affected.sessionId,
                    before = current.asSnapshot(),
                    after = restored[index].asSnapshot(),
                )
            }
        val correction =
            SessionCorrection(
                id = correctionId,
                type = SessionCorrectionType.RESTORE,
                at = at,
                changes = changes,
                restoresCorrectionId = original.id,
            )
        return HistoryEditResult.Applied(restored, correction)
    }

    /** Rules shared by every correction that touches an existing row, regardless of what it edits. */
    private fun rejectionForEditableRow(session: StudySession): HistoryRejection? =
        if (session.deleted) HistoryRejection.SESSION_DELETED else null

    private fun change(
        before: StudySession,
        after: StudySession,
    ): SessionSnapshotChange =
        SessionSnapshotChange(sessionId = before.id, before = before.asSnapshot(), after = after.asSnapshot())

    private fun applied(
        type: SessionCorrectionType,
        correctionId: String,
        at: Instant,
        sessions: List<StudySession>,
        changes: List<SessionSnapshotChange>,
    ): HistoryEditResult.Applied =
        HistoryEditResult.Applied(
            sessions = sessions,
            correction = SessionCorrection(id = correctionId, type = type, at = at, changes = changes),
        )

    /** Splits an elapsed total proportionally, keeping the two halves' sum exactly equal to the whole. */
    private fun SessionElapsed.splitBy(firstShare: Double): Pair<SessionElapsed, SessionElapsed> {
        val firstCounted = counted * firstShare
        val firstUnverified = unverified * firstShare
        val first = SessionElapsed(counted = firstCounted, unverified = firstUnverified)
        val second = SessionElapsed(counted = counted - firstCounted, unverified = unverified - firstUnverified)
        return first to second
    }
}
