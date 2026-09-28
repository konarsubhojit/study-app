package dev.studyflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.studyflow.core.database.entity.MaterialEntity
import dev.studyflow.core.database.entity.RECORD_SYNC_STATE_ID
import dev.studyflow.core.database.entity.ReminderEntity
import dev.studyflow.core.database.entity.SYNC_STATE_ID
import dev.studyflow.core.database.entity.SessionEventEntity
import dev.studyflow.core.database.entity.SessionWithEvents
import dev.studyflow.core.database.entity.StudySessionEntity
import dev.studyflow.core.database.entity.StudyTaskEntity
import dev.studyflow.core.database.entity.SubtaskEntity
import dev.studyflow.core.database.entity.SyncQueueEntity
import dev.studyflow.core.database.entity.SyncStateEntity
import dev.studyflow.core.database.entity.TaskTagEntity
import dev.studyflow.core.database.entity.TaskWithReminders
import dev.studyflow.core.database.entity.asEntity
import dev.studyflow.core.database.entity.asSyncRecord
import dev.studyflow.core.domain.sync.DocumentMergeOutcome
import dev.studyflow.core.domain.sync.DocumentSyncMerge
import dev.studyflow.core.domain.sync.SessionMergeOutcome
import dev.studyflow.core.domain.sync.SessionSyncMerge
import dev.studyflow.core.domain.sync.SyncDocument
import dev.studyflow.core.domain.sync.SyncEntityType
import dev.studyflow.core.domain.sync.SyncMaterialRecord
import dev.studyflow.core.domain.sync.SyncSessionRecord
import dev.studyflow.core.domain.sync.SyncTaskRecord
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

    /** The record stream's cursor (ADR 0018), kept in its own `sync_state` row. */
    @Query("SELECT cursor FROM sync_state WHERE id = '$RECORD_SYNC_STATE_ID'")
    public abstract suspend fun documentCursor(): String?

    /** The local copy of a task, tombstone included. */
    @Transaction
    @Query("SELECT * FROM study_tasks WHERE id = :id")
    public abstract suspend fun taskWithReminders(id: String): TaskWithReminders?

    /** The local copy of a catalogue row, tombstone included. */
    @Query("SELECT * FROM materials WHERE id = :id")
    public abstract suspend fun material(id: String): MaterialEntity?

    /** The local copy of a queued or inbound task or material, in the shape the merge compares. */
    public suspend fun document(
        entityType: SyncEntityType,
        id: String,
    ): SyncDocument? =
        when (entityType) {
            SyncEntityType.TASK -> taskWithReminders(id)?.asSyncRecord()
            SyncEntityType.MATERIAL -> material(id)?.asSyncRecord()
            SyncEntityType.SESSION -> null
        }

    /**
     * Merges one inbound record page and advances the record cursor, atomically — the same
     * guarantee as [applyPage], for tasks and materials (ADR 0018).
     *
     * @return how many changes were stored; a change the local copy beats is not counted.
     */
    @Transaction
    public open suspend fun applyDocumentPage(
        documents: List<SyncDocument>,
        nextCursor: String?,
        receivedAt: Instant,
    ): Int {
        var applied = 0
        documents.forEach { remote ->
            val local = document(remote.entityType, remote.id)
            val outcome = DocumentSyncMerge.merge(local, remote, receivedAt)
            if (outcome is DocumentMergeOutcome.Replaced) {
                storeDocument(outcome.document)
                if (local != null) dropSupersededQueueEntries(remote.entityType, remote.id, remote.updatedAt)
                applied++
            }
        }
        if (nextCursor != null) advanceDocumentCursor(nextCursor)
        return applied
    }

    private suspend fun storeDocument(document: SyncDocument) {
        when (document) {
            is SyncTaskRecord -> {
                val rows = document.task.asEntity(document.deviceId)
                upsertTask(rows.task.withResolvableSubject())
                deleteRemindersForTask(document.id)
                deleteTagsForTask(document.id)
                deleteSubtasksForTask(document.id)
                insertReminders(rows.reminders)
                insertTags(rows.tags)
                insertSubtasks(rows.subtasks)
            }

            is SyncMaterialRecord -> {
                upsertMaterial(document.material.asEntity(document.deviceId).withResolvableParents())
                deleteFtsForMaterial(document.id)
                insertFtsForMaterial(document.id)
            }
        }
    }

    /**
     * Forgets the signed-out account (ADR 0018): both cursors and the status go, and every syncable
     * row is queued again so the next account receives this device's data — the same state a
     * device is in the first time it signs in. Pending entries are kept as they are.
     */
    @Transaction
    public open suspend fun resetForAccountChange() {
        clearSyncState()
        requeueSessions()
        requeueTasks()
        requeueMaterials()
    }

    @Query("DELETE FROM sync_state")
    protected abstract suspend fun clearSyncState()

    @Query(
        """
        INSERT OR IGNORE INTO sync_queue (entity_type, entity_id, operation, updated_at, device_id)
        SELECT 'SESSION', id, 'UPSERT', updated_at, device_id FROM study_sessions
        WHERE status = 'STOPPED' AND deleted = 0
        """,
    )
    protected abstract suspend fun requeueSessions()

    @Query(
        """
        INSERT OR IGNORE INTO sync_queue (entity_type, entity_id, operation, updated_at, device_id)
        SELECT 'TASK', id, 'UPSERT', updated_at, device_id FROM study_tasks WHERE deleted = 0
        """,
    )
    protected abstract suspend fun requeueTasks()

    @Query(
        """
        INSERT OR IGNORE INTO sync_queue (entity_type, entity_id, operation, updated_at, device_id)
        SELECT 'MATERIAL', id, 'UPSERT', updated_at, device_id FROM materials
        WHERE deleted = 0 AND remote_key IS NOT NULL AND sync_state = 'SYNCED'
        """,
    )
    protected abstract suspend fun requeueMaterials()

    private suspend fun StudyTaskEntity.withResolvableSubject(): StudyTaskEntity =
        if (subjectId == null || subjectExists(subjectId)) this else copy(subjectId = null)

    /** Subjects and folders are not replicated; see [withResolvableSubject] on sessions. */
    private suspend fun MaterialEntity.withResolvableParents(): MaterialEntity =
        copy(
            subjectId = subjectId?.takeIf { subjectExists(it) },
            folderId = folderId?.takeIf { folderExists(it) },
        )

    @Query("SELECT EXISTS(SELECT 1 FROM folders WHERE id = :folderId)")
    protected abstract suspend fun folderExists(folderId: String): Boolean

    @Query(
        """
        INSERT INTO sync_state (id, cursor, last_success_at, last_error, last_attempt_at)
        VALUES ('$RECORD_SYNC_STATE_ID', :cursor, NULL, NULL, NULL)
        ON CONFLICT(id) DO UPDATE SET cursor = :cursor
        """,
    )
    protected abstract suspend fun advanceDocumentCursor(cursor: String)

    @Upsert
    protected abstract suspend fun upsertTask(task: StudyTaskEntity)

    @Query("DELETE FROM reminders WHERE task_id = :taskId")
    protected abstract suspend fun deleteRemindersForTask(taskId: String)

    @Query("DELETE FROM task_tags WHERE task_id = :taskId")
    protected abstract suspend fun deleteTagsForTask(taskId: String)

    @Query("DELETE FROM task_subtasks WHERE task_id = :taskId")
    protected abstract suspend fun deleteSubtasksForTask(taskId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertReminders(reminders: List<ReminderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertTags(tags: List<TaskTagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSubtasks(subtasks: List<SubtaskEntity>)

    @Upsert
    protected abstract suspend fun upsertMaterial(material: MaterialEntity)

    @Query("DELETE FROM material_fts WHERE material_id = :id")
    protected abstract suspend fun deleteFtsForMaterial(id: String)

    @Query(
        """
        INSERT INTO material_fts(material_id, display_name, notes)
        SELECT id, display_name, notes FROM materials WHERE id = :id AND deleted = 0
        """,
    )
    protected abstract suspend fun insertFtsForMaterial(id: String)

    @Upsert
    protected abstract suspend fun upsertSession(session: StudySessionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertEvents(events: List<SessionEventEntity>)
}
