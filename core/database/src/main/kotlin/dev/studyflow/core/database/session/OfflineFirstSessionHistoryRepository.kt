package dev.studyflow.core.database.session

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.studyflow.core.database.dao.SessionDao
import dev.studyflow.core.database.entity.SessionWithEvents
import dev.studyflow.core.database.entity.asEntities
import dev.studyflow.core.database.entity.asEntity
import dev.studyflow.core.database.entity.asExternalModel
import dev.studyflow.core.domain.result.toDomainError
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The Room-backed [SessionHistoryRepository].
 *
 * Every correction follows the same shape: load the rows it touches, ask [SessionHistoryEditor]
 * whether it is legal, and — if it is — write the resulting session rows together with their
 * audit entries in one [SessionDao.applyCorrection] transaction, so a reader never observes one
 * without the other.
 */
public class OfflineFirstSessionHistoryRepository(
    private val dao: SessionDao,
) : SessionHistoryRepository {
    /**
     * Serialises correction evaluation, mirroring `OfflineFirstSessionRepository`'s command lock:
     * loading the rows a correction touches and writing the result is a read-modify-write, and two
     * corrections racing on the same session could otherwise both read the pre-correction state.
     */
    private val correctionLock = Mutex()

    override fun observeTaskStudyTime(
        taskId: String,
        subjectId: String?,
    ): Flow<TaskStudyTime> =
        dao.observeAll().map { rows ->
            val sessions = rows.mapNotNull(SessionWithEvents::asExternalModel).filterNot(StudySession::deleted)
            TaskStudyTime(
                task =
                    sessions
                        .filter { it.taskId == taskId }
                        .fold(Duration.ZERO) { total, session -> total + session.elapsed.counted },
                subject =
                    sessions
                        .filter { subjectId != null && it.subjectId == subjectId }
                        .fold(Duration.ZERO) { total, session -> total + session.elapsed.counted },
            )
        }

    override fun historyPagingSource(filter: SessionHistoryFilter): PagingSource<Int, StudySession> =
        MappingPagingSource(dao.historyPaged(filter.subjectId, filter.from, filter.to)) { withEvents ->
            requireNotNull(withEvents.asExternalModel()) {
                "session ${withEvents.session.id} in the history query has no valid projection"
            }
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

    /**
     * Applies each delete as its own correction under one lock acquisition, so a bulk action still
     * leaves one audit entry per session (undo always targets a single session) rather than
     * inventing a group-level correction type the domain layer does not otherwise have.
     */
    override suspend fun bulkDelete(
        sessionIds: List<String>,
        correctionIdFor: (String) -> String,
        now: Instant,
    ): SessionHistoryCommandResult =
        correctionLock.withLock {
            runCatchingStorage {
                val appliedSessions = mutableListOf<StudySession>()
                var representative: SessionCorrection? = null
                for (sessionId in sessionIds) {
                    val command = HistoryEditCommand.Delete(sessionId)
                    when (val result = applyLocked(listOf(sessionId), command, correctionIdFor(sessionId), now)) {
                        is SessionHistoryCommandResult.Applied -> {
                            appliedSessions += result.sessions
                            representative = result.correction
                        }

                        is SessionHistoryCommandResult.Rejected, is SessionHistoryCommandResult.Failed -> {
                            return@runCatchingStorage result
                        }
                    }
                }
                // A bulk caller already has every correction's id via [correctionIdFor]; the
                // representative correction here is only for callers of the single-session shape
                // who want *a* correction reference back, not the definitive audit trail.
                SessionHistoryCommandResult.Applied(appliedSessions, requireNotNull(representative))
            }
        }

    override suspend fun undo(
        sessionId: String,
        correctionId: String,
        now: Instant,
    ): SessionHistoryCommandResult =
        correctionLock.withLock {
            runCatchingStorage {
                val group = dao.lastCorrectionGroup(sessionId)
                if (group.isEmpty()) {
                    return@runCatchingStorage SessionHistoryCommandResult.Rejected(HistoryRejection.NOTHING_TO_UNDO)
                }
                val correction = group.asExternalModel()
                val touchedIds = correction.changes.map { it.sessionId }
                val sessions = loadSessions(touchedIds)
                val command = HistoryEditCommand.Undo(correction)
                when (val result = SessionHistoryEditor.execute(command, sessions, correctionId, now)) {
                    is HistoryEditResult.Applied -> persist(result)
                    is HistoryEditResult.Rejected -> SessionHistoryCommandResult.Rejected(result.reason)
                }
            }
        }

    private suspend fun apply(
        sessionIds: List<String>,
        command: HistoryEditCommand,
        correctionId: String,
        at: Instant,
    ): SessionHistoryCommandResult =
        correctionLock.withLock {
            runCatchingStorage { applyLocked(sessionIds, command, correctionId, at) }
        }

    /** Must only run while holding [correctionLock]. */
    private suspend fun applyLocked(
        sessionIds: List<String>,
        command: HistoryEditCommand,
        correctionId: String,
        at: Instant,
    ): SessionHistoryCommandResult {
        val sessions = loadSessions(sessionIds)
        return when (val result = SessionHistoryEditor.execute(command, sessions, correctionId, at)) {
            is HistoryEditResult.Applied -> persist(result)
            is HistoryEditResult.Rejected -> SessionHistoryCommandResult.Rejected(result.reason)
        }
    }

    private suspend fun loadSessions(sessionIds: List<String>): Map<String, StudySession> =
        sessionIds
            .distinct()
            .mapNotNull { id -> dao.findWithEvents(id)?.asExternalModel()?.let { id to it } }
            .toMap()

    private suspend fun persist(result: HistoryEditResult.Applied): SessionHistoryCommandResult.Applied {
        val entities = result.sessions.map { it.asEntity() }
        val correctionEntities = result.correction.asEntities { sessionId -> "${result.correction.id}:$sessionId" }
        dao.applyCorrection(entities, correctionEntities)
        return SessionHistoryCommandResult.Applied(result.sessions, result.correction)
    }

    private inline fun runCatchingStorage(block: () -> SessionHistoryCommandResult): SessionHistoryCommandResult =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Throwable,
        ) {
            SessionHistoryCommandResult.Failed(failure.toDomainError())
        }
}

/** The ISO local calendar date [this] falls on in the device's default time zone. */
private fun Instant.toLocalDay(): String = toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

/**
 * Adapts a [PagingSource] by mapping each row through [transform], without eagerly materialising a
 * page's worth of the target type until Paging actually asks for that page.
 *
 * `paging-common` only ships a [PagingSource] &rarr; [PagingSource] mapping helper for
 * `PagingData` (post-load, in the UI-facing `Flow`); there is no equivalent for a raw
 * [PagingSource], so this is the source-level counterpart the repository needs to satisfy
 * [SessionHistoryRepository.historyPagingSource]'s `PagingSource<Int, StudySession>` contract while
 * Room continues to do the actual query and invalidation tracking on [delegate].
 */
private class MappingPagingSource<Value : Any>(
    private val delegate: PagingSource<Int, SessionWithEvents>,
    private val transform: (SessionWithEvents) -> Value,
) : PagingSource<Int, Value>() {
    init {
        delegate.registerInvalidatedCallback(::invalidate)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Value> =
        when (val result = delegate.load(params)) {
            is LoadResult.Page -> {
                LoadResult.Page(
                    data = result.data.map(transform),
                    prevKey = result.prevKey,
                    nextKey = result.nextKey,
                    itemsBefore = result.itemsBefore,
                    itemsAfter = result.itemsAfter,
                )
            }

            is LoadResult.Error -> {
                LoadResult.Error(result.throwable)
            }

            is LoadResult.Invalid -> {
                LoadResult.Invalid()
            }
        }

    // Keys are page offsets, independent of the row type, so the delegate's own refresh-key logic
    // applies unchanged; only the state's generic type needs bridging.
    override fun getRefreshKey(state: PagingState<Int, Value>): Int? =
        state.anchorPosition?.let { anchor ->
            val page = state.closestPageToPosition(anchor) ?: return@let null
            page.prevKey?.plus(1) ?: page.nextKey?.minus(1)
        }
}
