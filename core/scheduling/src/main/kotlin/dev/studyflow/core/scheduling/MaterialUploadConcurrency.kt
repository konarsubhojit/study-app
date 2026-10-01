package dev.studyflow.core.scheduling

import kotlinx.coroutines.sync.Semaphore

/**
 * A process-wide cap on how many materials upload at once.
 *
 * WorkManager's uniqueness is per material (`upload:$materialId`), so it will happily run every
 * queued upload in parallel. That went unnoticed while uploads were held behind an unmetered
 * network constraint, because the queue only ever drained on Wi-Fi — but the constraint releases
 * *all* of the waiting requests at the same moment, and with a 50 MiB per-file ceiling that means
 * every pending material competing for the same link, the same memory, and the same foreground
 * notification slot.
 *
 * A worker that cannot get a permit asks WorkManager to retry it rather than blocking on the
 * semaphore: a parked coroutine still holds a running worker slot (and its foreground service),
 * which is the resource this is trying to protect.
 */
internal object MaterialUploadConcurrency {
    /**
     * Two at a time: enough that one stalled transfer does not idle the link, few enough that the
     * device is not juggling several large streams and their progress notifications.
     */
    const val MAX_CONCURRENT_UPLOADS: Int = 2

    private val permits = Semaphore(MAX_CONCURRENT_UPLOADS)

    /** Runs [block] if a permit is free, otherwise returns `null` without waiting. */
    suspend fun <T> tryWithPermit(block: suspend () -> T): T? {
        if (!permits.tryAcquire()) return null
        return try {
            block()
        } finally {
            permits.release()
        }
    }
}
