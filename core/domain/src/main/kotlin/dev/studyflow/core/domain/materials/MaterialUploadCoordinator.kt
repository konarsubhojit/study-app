package dev.studyflow.core.domain.materials

/**
 * Schedules and controls a material's upload (issue #38).
 *
 * The catalogue (issue #37) never enqueues platform work itself — `:core:domain` has no
 * WorkManager to enqueue onto — so an import or a manual retry calls through this port instead,
 * and an Android module supplies the implementation that actually talks to `WorkManager`.
 */
public interface MaterialUploadCoordinator {
    /**
     * Queues [materialId] for upload, unless it is already synced or another material already
     * holds the same content under a different id.
     *
     * Safe to call more than once for the same material: the implementation is responsible for
     * making a repeated call a no-op rather than a second, duplicate upload.
     */
    public suspend fun enqueueUpload(materialId: String)

    /** Stops any in-flight or queued upload for [materialId]. */
    public fun cancelUpload(materialId: String)

    /** Re-queues [materialId] after a failed upload, even one that was marked non-retryable. */
    public suspend fun retryUpload(materialId: String)
}
