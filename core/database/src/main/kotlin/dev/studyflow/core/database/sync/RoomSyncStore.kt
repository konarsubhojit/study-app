package dev.studyflow.core.database.sync

import dev.studyflow.core.database.dao.SyncDao
import dev.studyflow.core.database.entity.SyncQueueEntity
import dev.studyflow.core.database.entity.asExternalModel
import dev.studyflow.core.database.entity.asSyncRecord
import dev.studyflow.core.domain.sync.SyncFailure
import dev.studyflow.core.domain.sync.SyncPage
import dev.studyflow.core.domain.sync.SyncQueueItem
import dev.studyflow.core.domain.sync.SyncSessionRecord
import dev.studyflow.core.domain.sync.SyncStatus
import dev.studyflow.core.domain.sync.SyncStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.time.Instant

/**
 * The Room-backed [SyncStore] (issue #55).
 *
 * Every method here is a thin delegation on purpose: the ordering rules live in
 * [dev.studyflow.core.domain.sync.SyncEngine] and the merge rules in
 * [dev.studyflow.core.domain.sync.SessionSyncMerge], both of which are pure and tested without a
 * database. What this class adds is the transactional guarantee — see [SyncDao.applyPage].
 */
public class RoomSyncStore(
    private val dao: SyncDao,
) : SyncStore {
    override fun observeStatus(): Flow<SyncStatus> =
        combine(dao.observePendingCount(), dao.observeState()) { pending, state ->
            SyncStatus(
                pendingCount = pending,
                lastSuccessAt = state?.lastSuccessAt,
                lastError = state?.lastError,
                lastAttemptAt = state?.lastAttemptAt,
            )
        }

    override suspend fun pending(limit: Int): List<SyncQueueItem> =
        dao.pending(limit).map(SyncQueueEntity::asExternalModel)

    override suspend fun sessionRecord(sessionId: String): SyncSessionRecord? =
        dao.sessionWithEvents(sessionId)?.asSyncRecord()

    override suspend fun acknowledge(sequences: List<Long>) {
        if (sequences.isEmpty()) return
        dao.acknowledge(sequences)
    }

    override suspend fun applyPage(page: SyncPage): Int = dao.applyPage(page.changes, page.nextCursor)

    override suspend fun cursor(): String? = dao.state()?.cursor

    override suspend fun recordSuccess(at: Instant) {
        dao.recordSuccess(at)
    }

    override suspend fun recordFailure(
        failure: SyncFailure,
        at: Instant,
    ) {
        dao.recordFailure(failure.message, at)
    }
}
