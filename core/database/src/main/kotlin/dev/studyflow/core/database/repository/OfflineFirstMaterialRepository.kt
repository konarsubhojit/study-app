package dev.studyflow.core.database.repository

import dev.studyflow.core.database.dao.MaterialDao
import dev.studyflow.core.database.entity.MaterialEntity
import dev.studyflow.core.database.entity.asEntity
import dev.studyflow.core.database.entity.asExternalModel
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * The local database is the source of truth for the materials catalogue; nothing here waits on a
 * network (issue #37).
 *
 * A freshly imported file is saved here the moment it is copied and hashed, in [SyncState.Pending];
 * the upload worker (issue #36) is what eventually moves it to `Synced`, entirely independently of
 * how the file arrived.
 */
public class OfflineFirstMaterialRepository(
    private val dao: MaterialDao,
) : MaterialRepository {
    override fun observeAll(): Flow<List<Material>> = dao.observeAll().asExternalModels()

    override fun observeInFolder(folderId: String?): Flow<List<Material>> =
        dao.observeInFolder(folderId).asExternalModels()

    override fun observeById(id: String): Flow<Material?> = dao.observeById(id).map { it?.material?.asExternalModel() }

    override suspend fun findByContentHash(contentHash: ContentHash): Material? =
        dao.findByContentHash(contentHash)?.asExternalModel()

    override suspend fun save(material: Material) {
        dao.save(material.asEntity())
    }

    override suspend fun updatePreviewState(
        id: String,
        pageIndex: Int?,
        positionMillis: Long?,
        playbackSpeed: Float?,
    ) {
        dao.updatePreviewState(id, pageIndex, positionMillis, playbackSpeed)
    }

    override suspend fun delete(id: String) {
        dao.softDelete(id, Clock.System.now())
    }

    private fun Flow<List<MaterialEntity>>.asExternalModels(): Flow<List<Material>> =
        map { rows -> rows.map { it.asExternalModel() } }
}
