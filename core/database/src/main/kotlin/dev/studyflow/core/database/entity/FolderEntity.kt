package dev.studyflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.time.Instant

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.CASCADE,
            deferred = true,
        ),
    ],
    indices = [Index(value = ["parent_id", "name", "id"], name = "index_folders_parent_id_name")],
)
public data class FolderEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "parent_id")
    val parentId: String?,
    val name: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
)
