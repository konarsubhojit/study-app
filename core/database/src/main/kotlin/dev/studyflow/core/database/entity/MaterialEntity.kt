package dev.studyflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.studyflow.core.model.ContentHash
import kotlin.time.Instant

@Entity(
    tableName = "materials",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(
            value = ["folder_id", "created_at", "id"],
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.ASC],
            name = "index_materials_folder_id_created_at",
        ),
        Index(value = ["sync_state", "created_at", "id"], name = "index_materials_sync_state_created_at"),
        Index(value = ["content_hash"], name = "index_materials_content_hash"),
        Index(
            value = ["created_at", "id"],
            orders = [Index.Order.DESC, Index.Order.ASC],
            name = "index_materials_created_at",
        ),
    ],
)
@Suppress("LongParameterList")
public data class MaterialEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "folder_id")
    val folderId: String?,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "content_hash")
    val contentHash: ContentHash,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "sync_state")
    val syncState: MaterialSyncState,
    @ColumnInfo(name = "uploaded_bytes")
    val uploadedBytes: Long?,
    @ColumnInfo(name = "upload_total_bytes")
    val uploadTotalBytes: Long?,
    @ColumnInfo(name = "failure_reason")
    val failureReason: String?,
    @ColumnInfo(name = "failure_retryable")
    val failureRetryable: Boolean?,
    @ColumnInfo(name = "local_uri")
    val localUri: String?,
    @ColumnInfo(name = "pinned_for_offline")
    val pinnedForOffline: Boolean,
    val encrypted: Boolean,
)

public enum class MaterialSyncState {
    PENDING,
    UPLOADING,
    SYNCED,
    FAILED,
}
