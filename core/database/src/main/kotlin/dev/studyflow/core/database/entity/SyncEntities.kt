package dev.studyflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.studyflow.core.domain.sync.SyncEntityType
import dev.studyflow.core.domain.sync.SyncOperation
import kotlin.time.Instant

/**
 * One local change waiting to be sent to the server (issue #55).
 *
 * Written by the same transaction as the mutation it describes — see
 * [dev.studyflow.core.database.dao.SessionDao.appendAndProject] — so there is no window in which a
 * change is durable locally but invisible to sync. A crash between the two is impossible rather
 * than unlikely.
 *
 * The unique index on `(entity_type, entity_id)` is what keeps the queue bounded: a session edited
 * five times offline is one pending entry, not five, and because the drain reads the row itself
 * rather than a stored payload, that single entry still sends the newest state.
 *
 * @property sequence insertion order, and the handle the drain acknowledges; re-queueing an entity
 *   replaces its entry under a new sequence, so an in-flight batch cannot discard a newer edit.
 */
@Entity(
    tableName = "sync_queue",
    indices = [
        Index(
            value = ["entity_type", "entity_id"],
            unique = true,
            name = "index_sync_queue_entity_type_entity_id",
        ),
    ],
)
public data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val sequence: Long = 0,
    @ColumnInfo(name = "entity_type")
    val entityType: SyncEntityType,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    val operation: SyncOperation,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
)

/**
 * The single row holding everything sync remembers between runs.
 *
 * [cursor] is advanced in the same transaction as the page it covers, which is what makes an
 * interrupted delta resumable: the stored cursor always describes changes that are already merged
 * locally, never ones that were merely received.
 */
@Entity(tableName = "sync_state")
public data class SyncStateEntity(
    @PrimaryKey
    val id: String = SYNC_STATE_ID,
    val cursor: String? = null,
    @ColumnInfo(name = "last_success_at")
    val lastSuccessAt: Instant? = null,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Instant? = null,
)

/** The primary key of the one [SyncStateEntity] row; sync state is per install, not per entity. */
public const val SYNC_STATE_ID: String = "default"
