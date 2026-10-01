package dev.studyflow.core.domain.materials

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

    /**
     * Why queued uploads are not running right now, or `null` when nothing is holding them back.
     *
     * A queued upload whose constraint is unmet never starts, so it logs nothing and changes no
     * state; without this the catalogue could only say "Pending upload", which reads exactly like
     * a fault. Implementations without platform constraints have nothing to wait on.
     */
    public val waitReason: Flow<UploadWaitReason?> get() = flowOf(null)
}

/** A constraint a queued upload is waiting on, in terms the UI can explain to the user. */
public enum class UploadWaitReason {
    /** Uploads are set to Wi-Fi only and the device is on a metered network. */
    WAITING_FOR_WIFI,

    /** The device has no usable network connection. */
    WAITING_FOR_NETWORK,

    /** The battery is low; uploads resume once it recovers or the device is charging. */
    WAITING_FOR_BATTERY,
}
