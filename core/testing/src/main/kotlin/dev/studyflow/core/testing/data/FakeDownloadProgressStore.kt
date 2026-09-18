package dev.studyflow.core.testing.data

import dev.studyflow.core.domain.materials.DownloadProgress
import dev.studyflow.core.domain.materials.DownloadProgressStore

public class FakeDownloadProgressStore : DownloadProgressStore {
    private val progressByMaterialId = mutableMapOf<String, DownloadProgress>()

    override suspend fun progress(materialId: String): DownloadProgress? = progressByMaterialId[materialId]

    override suspend fun save(progress: DownloadProgress) {
        progressByMaterialId[progress.materialId] = progress
    }

    override suspend fun clear(materialId: String) {
        progressByMaterialId.remove(materialId)
    }
}
