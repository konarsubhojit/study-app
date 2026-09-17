package dev.studyflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
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
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subject_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(
            value = ["deleted", "folder_id", "updated_at", "id"],
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC, Index.Order.ASC],
            name = "index_materials_folder_id_updated_at",
        ),
        Index(value = ["deleted", "subject_id", "updated_at", "id"], name = "index_materials_subject_id_updated_at"),
        Index(value = ["deleted", "mime_type", "updated_at", "id"], name = "index_materials_mime_type_updated_at"),
        Index(value = ["folder_id"], name = "index_materials_folder_id"),
        Index(value = ["subject_id"], name = "index_materials_subject_id"),
        Index(value = ["deleted", "sync_state", "updated_at", "id"], name = "index_materials_sync_state_updated_at"),
        Index(value = ["content_hash"], name = "index_materials_content_hash"),
        Index(
            value = ["deleted", "updated_at", "id"],
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.ASC],
            name = "index_materials_updated_at",
        ),
        Index(value = ["deleted", "size_bytes", "id"], name = "index_materials_size_bytes"),
        Index(value = ["deleted", "display_name", "id"], name = "index_materials_display_name"),
    ],
)
@Suppress("LongParameterList")
public data class MaterialEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "folder_id")
    val folderId: String?,
    @ColumnInfo(name = "subject_id")
    val subjectId: String?,
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
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
    val notes: String?,
    @ColumnInfo(name = "remote_key")
    val remoteKey: String?,
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
    @ColumnInfo(name = "local_path")
    val localPath: String?,
    @ColumnInfo(name = "pinned_for_offline")
    val pinnedForOffline: Boolean,
    val encrypted: Boolean,
    val deleted: Boolean,
)

public enum class MaterialSyncState {
    PENDING,
    UPLOADING,
    SYNCED,
    FAILED,
}

@Entity(tableName = "tags")
public data class TagEntity(
    @PrimaryKey
    val name: String,
)

@Entity(
    tableName = "material_tags",
    primaryKeys = ["material_id", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = MaterialEntity::class,
            parentColumns = ["id"],
            childColumns = ["material_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["name"],
            childColumns = ["tag"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tag", "material_id"], name = "index_material_tags_tag")],
)
public data class MaterialTagEntity(
    @ColumnInfo(name = "material_id")
    val materialId: String,
    val tag: String,
)

@Fts4
@Entity(tableName = "material_fts")
public data class MaterialFtsEntity(
    @ColumnInfo(name = "material_id")
    val materialId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    val notes: String?,
)

public data class MaterialWithTags(
    @Embedded
    val material: MaterialEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "name",
        associateBy =
            Junction(
                value = MaterialTagEntity::class,
                parentColumn = "material_id",
                entityColumn = "tag",
            ),
    )
    val tags: List<TagEntity> = emptyList(),
)
