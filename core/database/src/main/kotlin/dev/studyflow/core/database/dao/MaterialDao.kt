package dev.studyflow.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.studyflow.core.database.entity.MaterialEntity
import dev.studyflow.core.database.entity.MaterialSyncState
import dev.studyflow.core.database.entity.MaterialTagEntity
import dev.studyflow.core.database.entity.MaterialWithTags
import dev.studyflow.core.database.entity.TagEntity
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

@Dao
@Suppress("TooManyFunctions")
public abstract class MaterialDao {
    @Query("SELECT * FROM materials WHERE deleted = 0 ORDER BY updated_at DESC, id ASC")
    public abstract fun observeAll(): Flow<List<MaterialEntity>>

    public fun browsePaged(
        folderId: String?,
        subjectId: String?,
        mimeTypePrefix: String?,
        tag: String?,
        sort: MaterialSort = MaterialSort.UPDATED_AT_DESC,
    ): PagingSource<Int, MaterialEntity> = browsePagedQuery(folderId, subjectId, mimeTypePrefix, tag, sort.name)

    public fun searchPaged(
        query: String,
        folderId: String?,
        subjectId: String?,
        mimeTypePrefix: String?,
        tag: String?,
        sort: MaterialSort = MaterialSort.UPDATED_AT_DESC,
    ): PagingSource<Int, MaterialEntity> = searchPagedQuery(query, folderId, subjectId, mimeTypePrefix, tag, sort.name)

    @Query(
        """
        SELECT * FROM materials
        WHERE deleted = 0 AND folder_id IS :folderId
        ORDER BY updated_at DESC, id ASC
        """,
    )
    public abstract fun observeInFolder(folderId: String?): Flow<List<MaterialEntity>>

    @Transaction
    @Query("SELECT * FROM materials WHERE id = :id")
    public abstract fun observeById(id: String): Flow<MaterialWithTags?>

    @Query(
        """
        SELECT * FROM materials
        WHERE deleted = 0
            AND (:folderId IS NULL OR folder_id = :folderId)
            AND (:subjectId IS NULL OR subject_id = :subjectId)
            AND (:mimeTypePrefix IS NULL OR mime_type LIKE :mimeTypePrefix || '%')
            AND (:tag IS NULL OR id IN (SELECT material_id FROM material_tags WHERE tag = :tag))
        ORDER BY
            CASE WHEN :sort = 'UPDATED_AT_DESC' THEN updated_at END DESC,
            CASE WHEN :sort = 'UPDATED_AT_ASC' THEN updated_at END ASC,
            CASE WHEN :sort = 'SIZE_DESC' THEN size_bytes END DESC,
            CASE WHEN :sort = 'SIZE_ASC' THEN size_bytes END ASC,
            CASE WHEN :sort = 'NAME_DESC' THEN display_name END COLLATE NOCASE DESC,
            CASE WHEN :sort = 'NAME_ASC' THEN display_name END COLLATE NOCASE ASC,
            id ASC
        """,
    )
    protected abstract fun browsePagedQuery(
        folderId: String?,
        subjectId: String?,
        mimeTypePrefix: String?,
        tag: String?,
        sort: String,
    ): PagingSource<Int, MaterialEntity>

    @Query(
        """
        SELECT materials.* FROM materials
        JOIN material_fts ON material_fts.material_id = materials.id
        WHERE material_fts MATCH :query
            AND materials.deleted = 0
            AND (:folderId IS NULL OR materials.folder_id = :folderId)
            AND (:subjectId IS NULL OR materials.subject_id = :subjectId)
            AND (:mimeTypePrefix IS NULL OR materials.mime_type LIKE :mimeTypePrefix || '%')
            AND (:tag IS NULL OR materials.id IN (SELECT material_id FROM material_tags WHERE tag = :tag))
        ORDER BY
            CASE WHEN :sort = 'UPDATED_AT_DESC' THEN materials.updated_at END DESC,
            CASE WHEN :sort = 'UPDATED_AT_ASC' THEN materials.updated_at END ASC,
            CASE WHEN :sort = 'SIZE_DESC' THEN materials.size_bytes END DESC,
            CASE WHEN :sort = 'SIZE_ASC' THEN materials.size_bytes END ASC,
            CASE WHEN :sort = 'NAME_DESC' THEN materials.display_name END COLLATE NOCASE DESC,
            CASE WHEN :sort = 'NAME_ASC' THEN materials.display_name END COLLATE NOCASE ASC,
            materials.id ASC
        """,
    )
    protected abstract fun searchPagedQuery(
        query: String,
        folderId: String?,
        subjectId: String?,
        mimeTypePrefix: String?,
        tag: String?,
        sort: String,
    ): PagingSource<Int, MaterialEntity>

    @Query(
        """
        SELECT * FROM materials
        WHERE deleted = 0 AND sync_state IN (:states)
        ORDER BY updated_at ASC, id ASC
        LIMIT :limit
        """,
    )
    public abstract suspend fun uploadCandidates(
        states: Set<MaterialSyncState> = setOf(MaterialSyncState.PENDING, MaterialSyncState.FAILED),
        limit: Int,
    ): List<MaterialEntity>

    @Transaction
    public open suspend fun save(
        material: MaterialEntity,
        tags: Set<String> = emptySet(),
    ) {
        upsertMaterial(material)
        replaceTags(material.id, tags)
        reindexMaterial(material.id)
    }

    @Transaction
    public open suspend fun upsertAll(materials: List<MaterialEntity>) {
        upsertMaterials(materials)
        materials.forEach { reindexMaterial(it.id) }
    }

    @Transaction
    public open suspend fun replaceTags(
        materialId: String,
        tags: Set<String>,
    ) {
        val normalized = tags.map(String::trim).filter(String::isNotEmpty).distinct()
        deleteTagsForMaterial(materialId)
        insertTags(normalized.map(::TagEntity))
        insertMaterialTags(normalized.map { MaterialTagEntity(materialId, it) })
    }

    @Transaction
    public open suspend fun rename(
        id: String,
        displayName: String,
        notes: String?,
        updatedAt: Instant,
    ) {
        renameMaterial(id, displayName, notes, updatedAt)
        reindexMaterial(id)
    }

    @Query("UPDATE materials SET folder_id = :folderId, updated_at = :updatedAt WHERE id = :id")
    public abstract suspend fun move(
        id: String,
        folderId: String?,
        updatedAt: Instant,
    )

    @Transaction
    public open suspend fun softDelete(
        id: String,
        deletedAt: Instant,
    ) {
        tombstoneMaterial(id, deletedAt)
        deleteFtsForMaterial(id)
    }

    @Transaction
    public open suspend fun undoDelete(
        id: String,
        restoredAt: Instant,
    ) {
        restoreMaterial(id, restoredAt)
        reindexMaterial(id)
    }

    @Query("SELECT COUNT(*) FROM materials")
    public abstract suspend fun count(): Int

    @Upsert
    protected abstract suspend fun upsertMaterial(material: MaterialEntity)

    @Upsert
    protected abstract suspend fun upsertMaterials(materials: List<MaterialEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTags(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertMaterialTags(tags: List<MaterialTagEntity>)

    @Query("DELETE FROM material_tags WHERE material_id = :materialId")
    protected abstract suspend fun deleteTagsForMaterial(materialId: String)

    @Query(
        """
        UPDATE materials
        SET display_name = :displayName, notes = :notes, updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    protected abstract suspend fun renameMaterial(
        id: String,
        displayName: String,
        notes: String?,
        updatedAt: Instant,
    )

    @Query("UPDATE materials SET deleted = 1, updated_at = :deletedAt WHERE id = :id")
    protected abstract suspend fun tombstoneMaterial(
        id: String,
        deletedAt: Instant,
    )

    @Query("UPDATE materials SET deleted = 0, updated_at = :restoredAt WHERE id = :id")
    protected abstract suspend fun restoreMaterial(
        id: String,
        restoredAt: Instant,
    )

    @Query("DELETE FROM material_fts WHERE material_id = :id")
    protected abstract suspend fun deleteFtsForMaterial(id: String)

    @Query(
        """
        INSERT INTO material_fts(material_id, display_name, notes)
        SELECT id, display_name, notes FROM materials WHERE id = :id AND deleted = 0
        """,
    )
    protected abstract suspend fun insertFtsForMaterial(id: String)

    protected suspend fun reindexMaterial(id: String) {
        deleteFtsForMaterial(id)
        insertFtsForMaterial(id)
    }
}

public enum class MaterialSort {
    UPDATED_AT_DESC,
    UPDATED_AT_ASC,
    SIZE_DESC,
    SIZE_ASC,
    NAME_DESC,
    NAME_ASC,
}
