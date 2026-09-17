package dev.studyflow.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import dev.studyflow.core.database.entity.MaterialEntity
import dev.studyflow.core.database.entity.MaterialFtsEntity
import dev.studyflow.core.database.entity.MaterialSyncState
import dev.studyflow.core.database.entity.MaterialTagEntity
import dev.studyflow.core.database.entity.MaterialWithTags
import dev.studyflow.core.database.entity.TagEntity
import dev.studyflow.core.model.ContentHash
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
    ): PagingSource<Int, MaterialEntity> =
        browsePagedQuery(materialCatalogQuery(folderId, subjectId, mimeTypePrefix, tag, sort))

    public fun searchPaged(
        query: String,
        folderId: String?,
        subjectId: String?,
        mimeTypePrefix: String?,
        tag: String?,
        sort: MaterialSort = MaterialSort.UPDATED_AT_DESC,
    ): PagingSource<Int, MaterialEntity> =
        searchPagedQuery(materialCatalogQuery(folderId, subjectId, mimeTypePrefix, tag, sort, query))

    /**
     * The catalogue entry already stored under [contentHash], if any (issue #37).
     *
     * Backed by `index_materials_content_hash`, so a duplicate check costs an index lookup rather
     * than a scan, however large the catalogue grows.
     */
    @Query("SELECT * FROM materials WHERE content_hash = :contentHash LIMIT 1")
    public abstract suspend fun findByContentHash(contentHash: ContentHash): MaterialEntity?

    @Query(
        """
        SELECT * FROM materials
        WHERE deleted = 0 AND folder_id IS :folderId
        ORDER BY updated_at DESC, id ASC
        """,
    )
    public abstract fun observeInFolder(folderId: String?): Flow<List<MaterialEntity>>

    @Transaction
    @Query("SELECT * FROM materials WHERE id = :id AND deleted = 0")
    public abstract fun observeById(id: String): Flow<MaterialWithTags?>

    @RawQuery(observedEntities = [MaterialEntity::class, MaterialTagEntity::class])
    protected abstract fun browsePagedQuery(query: SupportSQLiteQuery): PagingSource<Int, MaterialEntity>

    @RawQuery(observedEntities = [MaterialEntity::class, MaterialTagEntity::class, MaterialFtsEntity::class])
    protected abstract fun searchPagedQuery(query: SupportSQLiteQuery): PagingSource<Int, MaterialEntity>

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

    @Query("SELECT COUNT(*) FROM materials WHERE deleted = 0")
    public abstract suspend fun countActive(): Int

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

    private fun materialCatalogQuery(
        folderId: String?,
        subjectId: String?,
        mimeTypePrefix: String?,
        tag: String?,
        sort: MaterialSort,
        searchQuery: String? = null,
    ): SupportSQLiteQuery {
        val args = mutableListOf<Any>()
        val sql =
            buildString {
                append("SELECT materials.* FROM materials")
                if (searchQuery != null) {
                    append(" JOIN material_fts ON material_fts.material_id = materials.id")
                }
                append(" WHERE materials.deleted = 0")
                if (searchQuery != null) {
                    append(" AND material_fts MATCH ?")
                    args += searchQuery
                }
                if (folderId != null) {
                    append(" AND materials.folder_id = ?")
                    args += folderId
                }
                if (subjectId != null) {
                    append(" AND materials.subject_id = ?")
                    args += subjectId
                }
                if (mimeTypePrefix != null) {
                    append(" AND materials.mime_type LIKE ?")
                    args += "$mimeTypePrefix%"
                }
                if (tag != null) {
                    append(" AND materials.id IN (SELECT material_id FROM material_tags WHERE tag = ?)")
                    args += tag
                }
                append(" ORDER BY ")
                append(sort.orderBy)
            }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
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

private val MaterialSort.orderBy: String
    get() =
        when (this) {
            MaterialSort.UPDATED_AT_DESC -> "materials.updated_at DESC, materials.id ASC"
            MaterialSort.UPDATED_AT_ASC -> "materials.updated_at ASC, materials.id ASC"
            MaterialSort.SIZE_DESC -> "materials.size_bytes DESC, materials.id ASC"
            MaterialSort.SIZE_ASC -> "materials.size_bytes ASC, materials.id ASC"
            MaterialSort.NAME_DESC -> "materials.display_name DESC, materials.id ASC"
            MaterialSort.NAME_ASC -> "materials.display_name ASC, materials.id ASC"
        }
