package dev.studyflow.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.studyflow.core.database.entity.MaterialEntity
import dev.studyflow.core.database.entity.MaterialSyncState
import kotlinx.coroutines.flow.Flow

@Dao
public interface MaterialDao {
    @Query("SELECT * FROM materials ORDER BY created_at DESC, id ASC")
    public fun observeAll(): Flow<List<MaterialEntity>>

    @Query(
        """
        SELECT * FROM materials
        WHERE (:folderId IS NULL AND folder_id IS NULL) OR folder_id = :folderId
        ORDER BY created_at DESC, id ASC
        """,
    )
    public fun observeInFolder(folderId: String?): Flow<List<MaterialEntity>>

    @Query(
        """
        SELECT * FROM materials
        WHERE sync_state IN (:states)
        ORDER BY created_at ASC, id ASC
        LIMIT :limit
        """,
    )
    public suspend fun uploadCandidates(
        states: Set<MaterialSyncState> = setOf(MaterialSyncState.PENDING, MaterialSyncState.FAILED),
        limit: Int,
    ): List<MaterialEntity>

    @Upsert
    public suspend fun upsert(material: MaterialEntity)

    @Upsert
    public suspend fun upsertAll(materials: List<MaterialEntity>)

    @Query("SELECT COUNT(*) FROM materials")
    public suspend fun count(): Int
}
