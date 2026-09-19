package dev.studyflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.studyflow.core.database.entity.SYNC_STATE_ID
import dev.studyflow.core.database.entity.SessionEventEntity
import dev.studyflow.core.database.entity.SessionWithEvents
import dev.studyflow.core.database.entity.StudySessionEntity
import dev.studyflow.core.database.entity.SyncQueueEntity
import dev.studyflow.core.database.entity.SyncStateEntity
import dev.studyflow.core.database.entity.asEntity
import dev.studyflow.core.database.entity.asSyncRecord
import dev.studyflow.core.domain.sync.SessionMergeOutcome
import dev.studyflow.core.domain.sync.SessionSyncMerge
import dev.studyflow.core.domain.sync.SyncEntityType
import dev.studyflow.core.domain.sync.SyncSessionRecord
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * The outbound queue, the inbound cursor and the merge that applies a delta (issue #55).
 *
 * The merge lives in a `@Transaction` method rather than in the repository above it because it is
 * a read-modify-write: loading the local copy, deciding the winner with [SessionSyncMerge] and
 * storing the result have to be one atomic step, or a timer command committed halfway through
 * could be silently overwritten by a change that never saw it.
 */
@Dao
@Suppress("TooManyFunctions")
public abstract class SyncDao {
    /**
     * Enqueues a change, replacing any still-pending entry for the same entity.
     *
     * Only ever called from inside another transaction — the one performing the mutation.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract suspend fun enqueue(entry: SyncQueueEntity)

    @Query("SELECT * FROM sync_queue ORDER BY sequence ASC LIMIT :limit")
    public abstract suspend fun pending(limit: Int): List<SyncQueueEntity>

    @Query("SELECT COUNT(*) FROM sync_queue")
    public abstract fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM sync_queue WHERE sequence IN (:sequences)")
    public abstract suspend fun acknowledge(sequences: List<Long>)

    @Query("SELECT * FROM sync_state WHERE id = '$SYNC_STATE_ID'")
    public abstract fun observeState(): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE id = '$SYNC_STATE_ID'")
    public abstract suspend fun state(): SyncStateEntity?

    /**
     * Records a completed run.
     *
     * A success clears [SyncStateEntity.lastError]: the settings screen must not keep showing a
     * failure the next attempt already recovered from.
     */
    @Query(
        """
        INSERT INTO sync_state (id, cursor, last_success_at, last_error, last_attempt_at)
        VALUES ('$SYNC_STATE_ID', NULL, :at, NULL, :at)
        ON CONFLICT(id) DO UPDATE SET last_success_at = :at, last_error = NULL, last_attempt_at = :at
        """,
    )
    public abstract suspend fun recordSuccess(at: Instant)

    @Query(
        """
        INSERT INTO sync_state (id, cursor, last_success_at, last_error, last_attempt_at)
        VALUES ('$SYNC_STATE_ID', NULL, NULL, :message, :at)
        ON CONFLICT(id) DO UPDATE SET last_error = :message, last_attempt_at = :at
        """,
    )
    public abstract suspend fun recordFailure(
        message: String,
        at: Instant,
    )

    /** The local copy of a session, tombstone included, or `null` when this device has none. */
    @Transaction
    @Query("SELECT * FROM study_sessions WHERE id = :sessionId")
    public abstract suspend fun sessionWithEvents(sessionId: String): SessionWithEvents?

    /**
     * Merges one inbound page and advances the cursor, atomically.
     *
     * Atomicity is the whole point: a run killed between the last merged change and the cursor
     * write would otherwise either re-apply a page (harmless, merges are idempotent) or skip one
     * (not harmless at all). Here neither is possible — the stored cursor only ever describes
     * changes this database already holds.
     *
     * @return how many changes were stored; a refused one (an active session) is not counted.
     */
    @Transaction
    public open suspend fun applyPage(
        records: List<SyncSessionRecord>,
        nextCursor: String?,
    ): Int {
        var applied = 0
        records.forEach { remote ->
            val local = sessionWithEvents(remote.session.id)?.asSyncRecord()
            val outcome = SessionSyncMerge.merge(local, remote)
            if (outcome is SessionMergeOutcome.Merged) {
                store(outcome, local)
                applied++
            }
        }
        if (nextCursor != null) advanceCursor(nextCursor)
        return applied
    }

    /**
     * Writes the merge result and retires any local change it superseded.
     *
     * Dropping the pending queue entry is what stops a stale local edit from resurrecting a
     * session the server deleted: the tombstone won the comparison, so re-sending the edit would
     * only ask the server to undo a deletion the user already made elsewhere. A *newer* local edit
     * carries a newer `updated_at` and is deliberately left queued.
     */
    private suspend fun store(
        outcome: SessionMergeOutcome.Merged,
        local: SyncSessionRecord?,
    ) {
        val merged = outcome.record
        upsertSession(merged.session.asEntity().withResolvableSubject())
        // IGNORE, not REPLACE: an event already stored is a fact this device recorded, and a
        // second delivery of the same id must never be allowed to rewrite it.
        insertEvents(merged.events.map { it.asEntity() })
        if (outcome.remoteWon && local != null) {
            dropSupersededQueueEntries(
                entityType = SyncEntityType.SESSION,
                entityId = merged.session.id,
                updatedAt = merged.session.updatedAt,
            )
        }
    }

    /**
     * Drops the session's subject when this device does not have it.
     *
     * Subjects are not replicated yet (the API describes no write for them), and `study_sessions`
     * has a foreign key to `subjects`. Keeping the dangling id would fail the whole page; dropping
     * it keeps the session — the thing the user's study time lives on — and it reappears the
     * moment subject sync lands.
     */
    private suspend fun StudySessionEntity.withResolvableSubject(): StudySessionEntity =
        if (subjectId == null || subjectExists(subjectId)) this else copy(subjectId = null)

    @Query("SELECT EXISTS(SELECT 1 FROM subjects WHERE id = :subjectId)")
    protected abstract suspend fun subjectExists(subjectId: String): Boolean

    @Query(
        """
        DELETE FROM sync_queue
        WHERE entity_type = :entityType AND entity_id = :entityId AND updated_at <= :updatedAt
        """,
    )
    protected abstract suspend fun dropSupersededQueueEntries(
        entityType: SyncEntityType,
        entityId: String,
        updatedAt: Instant,
    )

    @Query(
        """
        INSERT INTO sync_state (id, cursor, last_success_at, last_error, last_attempt_at)
        VALUES ('$SYNC_STATE_ID', :cursor, NULL, NULL, NULL)
        ON CONFLICT(id) DO UPDATE SET cursor = :cursor
        """,
    )
    protected abstract suspend fun advanceCursor(cursor: String)

    @Upsert
    protected abstract suspend fun upsertSession(session: StudySessionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertEvents(events: List<SessionEventEntity>)
}
