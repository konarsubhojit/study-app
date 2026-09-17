package dev.studyflow.core.database.repository

import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.database.dao.MaterialUploadPartDao
import dev.studyflow.core.database.entity.MaterialUploadPartEntity
import dev.studyflow.core.domain.materials.CompletedUploadPart
import dev.studyflow.core.domain.materials.UploadProgressStore

/**
 * Durable part progress backed by Room (issue #38).
 *
 * A part is written here the moment [ObjectStore.uploadPart][dev.studyflow.core.storage.ObjectStore.uploadPart]
 * acknowledges it, ahead of the next part being attempted — see [UploadProgressStore] for why that
 * ordering, not this class, is what makes a resumed upload safe.
 */
public class RoomUploadProgressStore(
    private val dao: MaterialUploadPartDao,
    private val clock: Clock,
) : UploadProgressStore {
    override suspend fun completedParts(materialId: String): List<CompletedUploadPart> =
        dao.completedParts(materialId).map { it.asExternalModel() }

    override suspend fun recordCompletedPart(
        materialId: String,
        part: CompletedUploadPart,
    ) {
        dao.upsert(
            MaterialUploadPartEntity(
                materialId = materialId,
                partNumber = part.number,
                etag = part.etag,
                sizeBytes = part.sizeBytes,
                completedAt = clock.now(),
            ),
        )
    }

    override suspend fun clear(materialId: String) {
        dao.clear(materialId)
    }

    private fun MaterialUploadPartEntity.asExternalModel(): CompletedUploadPart =
        CompletedUploadPart(number = partNumber, etag = etag, sizeBytes = sizeBytes)
}
