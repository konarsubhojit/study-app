package dev.studyflow.core.testing.data

import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [MaterialRepository], ordered the same way the database implementation is: newest
 * first, so a screen test sees the same list shape it would against Room.
 */
public class FakeMaterialRepository : MaterialRepository {
    private val materials = MutableStateFlow<List<Material>>(emptyList())

    override fun observeAll(): Flow<List<Material>> =
        materials.map { list -> list.filterNot(Material::deleted).sortedByDescending(Material::createdAt) }

    override fun observeInFolder(folderId: String?): Flow<List<Material>> =
        observeAll().map { list -> list.filter { it.folderId == folderId } }

    override fun observeById(id: String): Flow<Material?> =
        materials.map { list -> list.firstOrNull { it.id == id && !it.deleted } }

    override suspend fun findByContentHash(contentHash: ContentHash): Material? =
        materials.value.firstOrNull { it.contentHash == contentHash }

    override suspend fun save(material: Material) {
        materials.value = materials.value.filterNot { it.id == material.id } + material
    }

    override suspend fun updatePreviewState(
        id: String,
        pageIndex: Int?,
        positionMillis: Long?,
        playbackSpeed: Float?,
    ) {
        materials.value =
            materials.value.map { material ->
                if (material.id != id) {
                    material
                } else {
                    material.copy(
                        previewPageIndex = pageIndex ?: material.previewPageIndex,
                        previewPositionMillis = positionMillis ?: material.previewPositionMillis,
                        playbackSpeed = playbackSpeed ?: material.playbackSpeed,
                    )
                }
            }
    }

    override suspend fun delete(id: String) {
        materials.value =
            materials.value.map { material ->
                if (material.id == id) material.copy(deleted = true) else material
            }
    }
}
