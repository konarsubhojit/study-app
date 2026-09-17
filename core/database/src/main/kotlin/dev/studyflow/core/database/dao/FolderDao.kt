package dev.studyflow.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.studyflow.core.database.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
public interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY parent_id ASC, name ASC, id ASC")
    public fun observeAll(): Flow<List<FolderEntity>>

    @Query(
        """
        SELECT * FROM folders
        WHERE (:parentId IS NULL AND parent_id IS NULL) OR parent_id = :parentId
        ORDER BY name ASC, id ASC
        """,
    )
    public fun observeChildren(parentId: String?): Flow<List<FolderEntity>>

    @Upsert
    public suspend fun upsert(folder: FolderEntity)

    @Upsert
    public suspend fun upsertAll(folders: List<FolderEntity>)

    @Query("SELECT COUNT(*) FROM folders")
    public suspend fun count(): Int
}
