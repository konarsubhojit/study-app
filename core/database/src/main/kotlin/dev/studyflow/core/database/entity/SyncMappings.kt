package dev.studyflow.core.database.entity

import dev.studyflow.core.domain.sync.SyncEntityType
import dev.studyflow.core.domain.sync.SyncOperation
import dev.studyflow.core.domain.sync.SyncQueueItem
import dev.studyflow.core.domain.sync.SyncSessionRecord
import dev.studyflow.core.model.StudySession

// Room rows in the shapes sync speaks (issue #55). Kept apart from `EntityMappings.kt` because
// these translate towards the *sync* domain rather than towards the app's own model: what travels
// between devices is a session plus its whole event log, which is a different question from what a
// screen renders.

/**
 * The local copy in the shape the sync merge rules work on.
 *
 * `null` when the row has no event log to project from — a session only exists once it has been
 * started, so there is nothing for a remote change to be merged against.
 *
 * [StudySession.updatedAt] is taken from the stored column rather than from the projection: the
 * reducer derives it from the last event, and a metadata-only correction — renaming a note,
 * moving a session to another subject, deleting it — appends no event at all. Conflict resolution
 * has to compare the last time the row was *written*, which is precisely what the column records
 * and what the queue entry beside it carries.
 */
public fun SessionWithEvents.asSyncRecord(): SyncSessionRecord? {
    val projection = asExternalModel() ?: return null
    return SyncSessionRecord(
        session = projection.copy(updatedAt = session.updatedAt),
        events = events.map(SessionEventEntity::asExternalModel),
    )
}

/** The queue row this session's mutation enqueues; a tombstoned row queues a delete. */
public fun StudySessionEntity.asSyncQueueEntity(): SyncQueueEntity =
    SyncQueueEntity(
        entityType = SyncEntityType.SESSION,
        entityId = id,
        operation = if (deleted) SyncOperation.DELETE else SyncOperation.UPSERT,
        updatedAt = updatedAt,
        deviceId = deviceId,
    )

public fun SyncQueueEntity.asExternalModel(): SyncQueueItem =
    SyncQueueItem(
        sequence = sequence,
        entityType = entityType,
        entityId = entityId,
        operation = operation,
        updatedAt = updatedAt,
        deviceId = deviceId,
    )
