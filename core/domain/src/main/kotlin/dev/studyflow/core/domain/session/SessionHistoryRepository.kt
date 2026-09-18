package dev.studyflow.core.domain.session

import androidx.paging.PagingSource
import dev.studyflow.core.domain.result.DomainError
import dev.studyflow.core.model.StudySession
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Narrows the history list to a subject, a date range, or both.
 *
 * `null` means "no constraint" throughout, matching the nullable-filter convention already used by
 * `MaterialDao`'s catalogue query.
 */
public data class SessionHistoryFilter(
    val subjectId: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
) {
    /** True once the user has narrowed the list, so an empty result can be explained. */
    public val isNarrowed: Boolean get() = subjectId != null || from != null || to != null
}

/**
 * Time spent on one subject on one calendar day, for a history day header.
 *
 * @property day the local calendar date the header groups by, as an ISO string (`YYYY-MM-DD`) so
 *   this stays a plain-data model independent of any particular time zone library's calendar type.
 */
public data class DailySubjectTotal(
    val day: String,
    val subjectId: String?,
    val totalCounted: kotlin.time.Duration,
)

/** Counted study time attributed to one task and its subject. */
public data class TaskStudyTime(
    val task: Duration = Duration.ZERO,
    val subject: Duration = Duration.ZERO,
)

/** Outcome of a [SessionHistoryRepository] correction. */
public sealed interface SessionHistoryCommandResult {
    /** The correction was legal and durably recorded. */
    public data class Applied(
        val sessions: List<StudySession>,
        val correction: SessionCorrection,
    ) : SessionHistoryCommandResult

    /** The correction made no sense against the stored rows, so nothing was written. */
    public data class Rejected(
        val reason: HistoryRejection,
    ) : SessionHistoryCommandResult

    /** Storage itself failed; whether anything was written is decided by the transaction. */
    public data class Failed(
        val error: DomainError,
    ) : SessionHistoryCommandResult
}

/**
 * Durable storage for session history: paged browsing, per-day subject totals, and corrections.
 *
 * This sits beside [SessionRepository] rather than folded into it: [SessionRepository] is the
 * timer's write path and is optimised for "the one active session"; this is the history screen's
 * read/correct path and is optimised for "all of a device's stopped sessions, ten thousand deep,
 * without loading them into memory at once" (issue #32). Both ultimately read and write the same
 * `study_sessions` table.
 */
@Suppress("TooManyFunctions")
public interface SessionHistoryRepository {
    /**
     * Observes totals from session projections so timing edits, splits, merges and deletes
     * automatically invalidate the result.
     */
    public fun observeTaskStudyTime(
        taskId: String,
        subjectId: String?,
    ): Flow<TaskStudyTime>

    /**
     * A paging source over stopped, non-deleted sessions matching [filter], newest first.
     *
     * Callers group the emitted pages into day headers themselves (see `insertSeparators` in
     * Paging 3) rather than the query doing it in SQL: a session's calendar day depends on the
     * viewer's time zone, which is a presentation concern, not a storage one.
     */
    public fun historyPagingSource(
        filter: SessionHistoryFilter = SessionHistoryFilter(),
    ): PagingSource<Int, StudySession>

    /**
     * Per-subject totals for each day currently visible in [sessions].
     *
     * Deliberately computed from an already-loaded page rather than a separate full-table
     * aggregate: folding every session's event log to get an exact total is cheap for one page and
     * expensive for ten thousand rows, and the whole point of paging is to never touch all ten
     * thousand at once.
     */
    public fun dailyTotals(sessions: List<StudySession>): List<DailySubjectTotal>

    public suspend fun editTiming(
        sessionId: String,
        startedAt: Instant,
        endedAt: Instant,
        correctionId: String,
        at: Instant,
    ): SessionHistoryCommandResult

    public suspend fun editSubject(
        sessionId: String,
        subjectId: String?,
        correctionId: String,
        at: Instant,
    ): SessionHistoryCommandResult

    public suspend fun editNote(
        sessionId: String,
        note: String?,
        correctionId: String,
        at: Instant,
    ): SessionHistoryCommandResult

    public suspend fun split(
        sessionId: String,
        at: Instant,
        newSessionId: String,
        correctionId: String,
        now: Instant,
    ): SessionHistoryCommandResult

    public suspend fun merge(
        sessionIds: List<String>,
        correctionId: String,
        now: Instant,
    ): SessionHistoryCommandResult

    @Suppress("LongParameterList")
    public suspend fun manualEntry(
        deviceId: String,
        subjectId: String?,
        note: String?,
        startedAt: Instant,
        endedAt: Instant,
        sessionId: String,
        correctionId: String,
        now: Instant,
    ): SessionHistoryCommandResult

    public suspend fun delete(
        sessionId: String,
        correctionId: String,
        now: Instant,
    ): SessionHistoryCommandResult

    /** Deletes several sessions as one bulk action; each gets its own correction record. */
    public suspend fun bulkDelete(
        sessionIds: List<String>,
        correctionIdFor: (String) -> String,
        now: Instant,
    ): SessionHistoryCommandResult

    /** Reverses the most recent correction recorded against [sessionId], if any. */
    public suspend fun undo(
        sessionId: String,
        correctionId: String,
        now: Instant,
    ): SessionHistoryCommandResult
}
