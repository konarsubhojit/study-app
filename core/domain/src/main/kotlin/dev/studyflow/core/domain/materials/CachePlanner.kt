package dev.studyflow.core.domain.materials

import kotlin.time.Instant

/**
 * Decides which cached files to drop when the offline cache outgrows its budget.
 *
 * ### Why this is not just an LRU
 *
 * A plain least-recently-used cache would happily delete two things it must never touch:
 *
 * 1. **Files the user pinned for offline use.** Pinning is a promise; evicting a pinned file breaks
 *    it precisely when the user is on a train with no signal, which is the only time they'd notice.
 * 2. **Files that have not finished uploading.** The local copy *is* the only copy. Evicting it
 *    does not free a cached download, it destroys the user's data.
 *
 * So the eviction order is: never touch the untouchable, then least-recently-used among the rest,
 * and if that is still not enough, say so rather than pretending the cache is within budget.
 */
public object CachePlanner {
    /**
     * Chooses files to evict so the cache fits within [maxBytes].
     *
     * @param entries every file currently occupying cache space.
     * @return the eviction plan, including any [EvictionPlan.shortfallBytes] that cannot be
     *   reclaimed without breaking a promise.
     */
    public fun planEviction(
        entries: List<CacheEntry>,
        maxBytes: Long,
    ): EvictionPlan {
        require(maxBytes >= 0) { "maxBytes must not be negative, was $maxBytes" }

        val totalBytes = entries.sumOf { it.sizeBytes }
        val excess = totalBytes - maxBytes
        if (excess <= 0) return EvictionPlan(evict = emptyList(), retainedBytes = totalBytes)

        val (protected, evictable) = entries.partition { it.isProtected }
        val evict = mutableListOf<CacheEntry>()
        var reclaimed = 0L

        // Oldest access first; ties broken by size so a big stale file goes before a small one.
        for (entry in evictable.sortedWith(compareBy({ it.lastAccessedAt }, { -it.sizeBytes }))) {
            if (reclaimed >= excess) break
            evict += entry
            reclaimed += entry.sizeBytes
        }

        val shortfall = (excess - reclaimed).coerceAtLeast(0)
        return EvictionPlan(
            evict = evict,
            retainedBytes = totalBytes - reclaimed,
            shortfallBytes = shortfall,
            protectedBytes = protected.sumOf { it.sizeBytes },
        )
    }
}

/**
 * A file occupying cache space.
 *
 * @property pinned the user asked for this to stay available offline.
 * @property uploaded the bytes also exist in object storage, so the local copy is expendable.
 */
public data class CacheEntry(
    val materialId: String,
    val sizeBytes: Long,
    val lastAccessedAt: Instant,
    val pinned: Boolean = false,
    val uploaded: Boolean = true,
) {
    init {
        require(materialId.isNotBlank()) { "CacheEntry.materialId must not be blank" }
        require(sizeBytes >= 0) { "CacheEntry.sizeBytes must not be negative, was $sizeBytes" }
    }

    /** True when evicting this entry would break a promise or lose data outright. */
    public val isProtected: Boolean get() = pinned || !uploaded
}

/**
 * What to delete, and what could not be deleted.
 *
 * @property shortfallBytes how far over budget the cache will still be after eviction, because the
 *   remainder is pinned or not yet uploaded. Non-zero means the UI should tell the user to unpin
 *   something or wait for uploads, rather than the app silently exceeding its budget.
 */
public data class EvictionPlan(
    val evict: List<CacheEntry>,
    val retainedBytes: Long,
    val shortfallBytes: Long = 0,
    val protectedBytes: Long = 0,
) {
    /** Bytes reclaimed by carrying out this plan. */
    public val reclaimedBytes: Long get() = evict.sumOf { it.sizeBytes }

    /** True when the cache cannot be brought within budget without user action. */
    public val isOverBudget: Boolean get() = shortfallBytes > 0
}
