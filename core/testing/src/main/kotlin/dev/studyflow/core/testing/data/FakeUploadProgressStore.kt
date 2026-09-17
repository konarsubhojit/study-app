package dev.studyflow.core.testing.data

import dev.studyflow.core.domain.materials.CompletedUploadPart
import dev.studyflow.core.domain.materials.UploadProgressStore
import java.util.concurrent.ConcurrentHashMap

/** In-memory [UploadProgressStore], keyed by material id, for tests that never touch Room. */
public class FakeUploadProgressStore : UploadProgressStore {
    private val partsByMaterial = ConcurrentHashMap<String, MutableList<CompletedUploadPart>>()

    override suspend fun completedParts(materialId: String): List<CompletedUploadPart> =
        partsByMaterial[materialId].orEmpty().toList()

    override suspend fun recordCompletedPart(
        materialId: String,
        part: CompletedUploadPart,
    ) {
        val parts = partsByMaterial.getOrPut(materialId) { mutableListOf() }
        parts.removeAll { it.number == part.number }
        parts += part
    }

    override suspend fun clear(materialId: String) {
        partsByMaterial.remove(materialId)
    }
}
