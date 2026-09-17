package dev.studyflow.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.studyflow.core.database.entity.MaterialUploadPartEntity

/**
 * Per-part progress of a material's resumable upload (issue #38).
 *
 * [completedParts] is read once at the start of every worker run to decide which parts of the plan
 * still need sending; [upsert] is called immediately after each part is acknowledged, before the
 * next one is attempted, so a process death between two parts loses at most the part in flight.
 */
@Dao
@Suppress("UnnecessaryAbstractClass") // matches every other Room DAO in this module (see MaterialDao).
public abstract class MaterialUploadPartDao {
    @Query("SELECT * FROM material_upload_parts WHERE material_id = :materialId ORDER BY part_number ASC")
    public abstract suspend fun completedParts(materialId: String): List<MaterialUploadPartEntity>

    @Upsert
    public abstract suspend fun upsert(part: MaterialUploadPartEntity)

    /** Called once the upload finishes, successfully or permanently, so no stale rows linger. */
    @Query("DELETE FROM material_upload_parts WHERE material_id = :materialId")
    public abstract suspend fun clear(materialId: String)
}
