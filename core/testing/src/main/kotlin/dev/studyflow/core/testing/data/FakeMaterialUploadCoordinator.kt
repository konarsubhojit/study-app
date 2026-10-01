package dev.studyflow.core.testing.data

import dev.studyflow.core.domain.materials.MaterialUploadCoordinator
import dev.studyflow.core.domain.materials.UploadWaitReason
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Records coordinator calls instead of touching WorkManager, for view models and use cases that
 * only need to know an upload *would* have been enqueued, cancelled or retried.
 */
public class FakeMaterialUploadCoordinator : MaterialUploadCoordinator {
    public val enqueued: MutableList<String> = mutableListOf()
    public val cancelled: MutableList<String> = mutableListOf()
    public val retried: MutableList<String> = mutableListOf()

    /** Set to make queued uploads read as held back by a constraint. */
    override val waitReason: MutableStateFlow<UploadWaitReason?> = MutableStateFlow(null)

    override suspend fun enqueueUpload(materialId: String) {
        enqueued += materialId
    }

    override fun cancelUpload(materialId: String) {
        cancelled += materialId
    }

    override suspend fun retryUpload(materialId: String) {
        retried += materialId
    }
}
