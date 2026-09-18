package dev.studyflow.core.testing.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.studyflow.core.domain.result.DomainError
import dev.studyflow.core.domain.session.DailySubjectTotal
import dev.studyflow.core.domain.session.HistoryEditCommand
import dev.studyflow.core.domain.session.HistoryEditResult
import dev.studyflow.core.domain.session.HistoryRejection
import dev.studyflow.core.domain.session.SessionCorrection
import dev.studyflow.core.domain.session.SessionHistoryCommandResult
import dev.studyflow.core.domain.session.SessionHistoryEditor
import dev.studyflow.core.domain.session.SessionHistoryFilter
import dev.studyflow.core.domain.session.SessionHistoryRepository
import dev.studyflow.core.domain.session.TaskStudyTime
import dev.studyflow.core.model.StudySession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * In-memory [SessionHistoryRepository] for ViewModel tests.
 *
 * Reuses the real [SessionHistoryEditor] — the same pure domain logic the Room-backed repository
 * delegates to — so a feature test exercises the actual correction rules (guard clauses, rejection
 * reasons) rather than a hand-rolled stand-in that could silently drift from production behaviour.
 * Only the storage (`sessions`/`corrections` maps) and paging (an in-memory [PagingSource]) are
 * faked.
 */
public class FakeSessionHistoryRepository(
    seed: List<StudySession> = emptyList(),
) : SessionHistoryRepository {
    private val sessions = MutableStateFlow(seed.associateBy { it.id })

    /** Every correction ever applied, in application order, for assertions in tests. */
    public val corrections: MutableList<SessionCorrection> = mutableListOf()

    /** Set by a test to force the next command to fail as [SessionHistoryCommandResult.Failed]. */
    public var failNext: Boolean = false

    override fun observeTaskStudyTime(
        taskId: String,
        subjectId: String?,
    ): Flow<TaskStudyTime> =
        sessions.map { values ->
            val visible = values.values.filterNot(StudySession::deleted)
            TaskStudyTime(
                task =
                    visible
                        .filter { it.taskId == taskId }
                        .fold(Duration.ZERO) { total, session -> total + session.elapsed.counted },
                subject =
                    visible
                        .filter { subjectId != null && it.subjectId == subjectId }
                        .fold(Duration.ZERO) { total, session -> total + session.elapsed.counted },
            )
        }

    override fun historyPagingSource(filter: SessionHistoryFilter): PagingSource<Int, StudySession> =
        FakeHistoryPagingSource {
            sessions.value.values
                .filter { !it.deleted }
                .filter { filter.subjectId == null || it.subjectId == filter.subjectId }
                .filter { session -> filter.from?.let { session.startedAt >= it } ?: true }
                .filter { session -> filter.to?.let { session.startedAt <= it } ?: true }
                .sortedWith(compareByDescending<StudySession> { it.startedAt }.thenByDescending { it.id })
        }

    override fun dailyTotals(sessions: List<StudySession>): List<DailySubjectTotal> =
        sessions
            .groupBy { it.startedAt.toLocalDay() to it.subjectId }
            .map { (key, forDay) ->
                val (day, subjectId) = key
                DailySubjectTotal(
                    day = day,
                    subjectId = subjectId,
                    totalCounted = forDay.fold(Duration.ZERO) { acc, session -> acc + session.elapsed.counted },
                )
            }

    override suspend fun editTiming(
        sessionId: String,
        startedAt: Instant,
        endedAt: Instant,
        correctionId: String,
        at: Instant,
    ): SessionHistoryCommandResult =
        apply(listOf(sessionId), HistoryEditCommand.EditTiming(sessionId, startedAt, endedAt), correctionId, at)

    override suspend fun editSubject(
        sessionId: String,
        subjectId: String?,
        correctionId: String,
        at: Instant,
    ): SessionHistoryCommandResult =
        apply(listOf(sessionId), HistoryEditCommand.EditSubject(sessionId, subjectId), correctionId, at)

    override suspend fun editNote(
        sessionId: String,
        note: String?,
        correctionId: String,
        at: Instant,
    ): SessionHistoryCommandResult =
        apply(listOf(sessionId), HistoryEditCommand.EditNote(sessionId, note), correctionId, at)

    override suspend fun split(
        sessionId: String,
        at: Instant,
        newSessionId: String,
        correctionId: String,
        now: Instant,
    ): SessionHistoryCommandResult =
        apply(listOf(sessionId), HistoryEditCommand.Split(sessionId, at, newSessionId), correctionId, now)

    override suspend fun merge(
        sessionIds: List<String>,
        correctionId: String,
        now: Instant,
    ): SessionHistoryCommandResult = apply(sessionIds, HistoryEditCommand.Merge(sessionIds), correctionId, now)

    @Suppress("LongParameterList")
    override suspend fun manualEntry(
        deviceId: String,
        subjectId: String?,
        note: String?,
        startedAt: Instant,
        endedAt: Instant,
        sessionId: String,
        correctionId: String,
        now: Instant,
    ): SessionHistoryCommandResult =
        apply(
            emptyList(),
            HistoryEditCommand.ManualEntry(sessionId, deviceId, subjectId, note, startedAt, endedAt),
            correctionId,
            now,
        )

    override suspend fun delete(
        sessionId: String,
        correctionId: String,
        now: Instant,
    ): SessionHistoryCommandResult = apply(listOf(sessionId), HistoryEditCommand.Delete(sessionId), correctionId, now)

    override suspend fun bulkDelete(
        sessionIds: List<String>,
        correctionIdFor: (String) -> String,
        now: Instant,
    ): SessionHistoryCommandResult {
        var last: SessionHistoryCommandResult.Applied? = null
        for (sessionId in sessionIds) {
            when (val result = delete(sessionId, correctionIdFor(sessionId), now)) {
                is SessionHistoryCommandResult.Applied -> last = result
                is SessionHistoryCommandResult.Rejected, is SessionHistoryCommandResult.Failed -> return result
            }
        }
        return last ?: SessionHistoryCommandResult.Rejected(HistoryRejection.NOTHING_TO_UNDO)
    }

    override suspend fun undo(
        sessionId: String,
        correctionId: String,
        now: Instant,
    ): SessionHistoryCommandResult {
        val group =
            corrections.lastOrNull { correction -> correction.changes.any { it.sessionId == sessionId } }
                ?: return SessionHistoryCommandResult.Rejected(HistoryRejection.NOTHING_TO_UNDO)
        val touchedIds = group.changes.map { it.sessionId }
        return apply(touchedIds, HistoryEditCommand.Undo(group), correctionId, now)
    }

    private fun apply(
        sessionIds: List<String>,
        command: HistoryEditCommand,
        correctionId: String,
        at: Instant,
    ): SessionHistoryCommandResult {
        if (failNext) {
            failNext = false
            return SessionHistoryCommandResult.Failed(DomainError.Unknown)
        }
        val loaded = sessionIds.distinct().mapNotNull { id -> sessions.value[id]?.let { id to it } }.toMap()
        return when (val result = SessionHistoryEditor.execute(command, loaded, correctionId, at)) {
            is HistoryEditResult.Applied -> {
                sessions.value = sessions.value + result.sessions.associateBy(StudySession::id)
                corrections += result.correction
                SessionHistoryCommandResult.Applied(result.sessions, result.correction)
            }

            is HistoryEditResult.Rejected -> {
                SessionHistoryCommandResult.Rejected(result.reason)
            }
        }
    }
}

private fun Instant.toLocalDay(): String = toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

/**
 * A [PagingSource] that always returns [snapshot] as a single page.
 *
 * A ViewModel test only needs "the current sessions render", not real multi-page behaviour, so
 * this deliberately skips windowing rather than reimplementing Paging's key arithmetic in a fake.
 */
private class FakeHistoryPagingSource(
    private val snapshot: () -> List<StudySession>,
) : PagingSource<Int, StudySession>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, StudySession> =
        LoadResult.Page(data = snapshot(), prevKey = null, nextKey = null)

    override fun getRefreshKey(state: PagingState<Int, StudySession>): Int? = null
}
