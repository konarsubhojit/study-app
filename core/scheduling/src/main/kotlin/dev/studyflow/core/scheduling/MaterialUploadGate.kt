package dev.studyflow.core.scheduling

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Caps how many material uploads transfer at once, across every material.
 *
 * WorkManager's uniqueness is per material, so nothing else bounds this: when a Wi-Fi-only
 * constraint is met, every queued upload is released in the same instant, and each can be a
 * 50 MiB file. A worker over the cap simply waits here for a permit, still holding its own place.
 */
public class MaterialUploadGate(
    maxConcurrentUploads: Int = DEFAULT_MAX_CONCURRENT_UPLOADS,
) {
    private val permits = Semaphore(maxConcurrentUploads)

    public suspend fun <T> withPermit(block: suspend () -> T): T = permits.withPermit { block() }

    public companion object {
        public const val DEFAULT_MAX_CONCURRENT_UPLOADS: Int = 2
    }
}
