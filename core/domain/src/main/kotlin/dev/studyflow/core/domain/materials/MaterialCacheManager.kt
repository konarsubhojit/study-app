package dev.studyflow.core.domain.materials

import dev.studyflow.core.model.Material
import dev.studyflow.core.model.MaterialKind
import dev.studyflow.core.model.SyncState
import kotlin.time.Instant

/** Domain rules for offline material cache accounting, prefetch, export and cleanup (issue #39). */
public object MaterialCacheManager {
    public fun usage(
        items: List<CachedMaterial>,
        largestLimit: Int = DEFAULT_LARGEST_LIMIT,
    ): CacheUsage {
        require(largestLimit >= 0) { "largestLimit must not be negative, was $largestLimit" }
        val cached = items.filter { it.material.localPath != null }
        return CacheUsage(
            totalBytes = cached.sumOf { it.material.sizeBytes },
            pinnedBytes = cached.filter { it.material.pinnedForOffline }.sumOf { it.material.sizeBytes },
            unsyncedBytes = cached.filterNot { it.material.isSynced }.sumOf { it.material.sizeBytes },
            evictableBytes =
                cached
                    .filter { !it.material.pinnedForOffline && it.material.isSynced }
                    .sumOf { it.material.sizeBytes },
            byKind = cached.groupingBy { it.material.kind }.fold(0L) { total, item -> total + item.material.sizeBytes },
            largestItems = cached.sortedByDescending { it.material.sizeBytes }.take(largestLimit),
        )
    }

    public fun planQuotaCleanup(
        items: List<CachedMaterial>,
        quotaBytes: Long,
    ): CacheCleanupPlan = cleanupPlan(items, maxBytes = quotaBytes)

    public fun planFreeUpSpace(items: List<CachedMaterial>): CacheCleanupPlan {
        val protectedBytes =
            items
                .filter { it.material.localPath != null && (it.material.pinnedForOffline || !it.material.isSynced) }
                .sumOf { it.material.sizeBytes }
        return cleanupPlan(items, maxBytes = protectedBytes)
    }

    public fun prefetchCandidates(
        items: List<Material>,
        conditions: PrefetchConditions,
        maxItemBytes: Long = DEFAULT_PREFETCH_MAX_ITEM_BYTES,
        limit: Int = DEFAULT_PREFETCH_LIMIT,
    ): List<Material> {
        require(maxItemBytes >= 0) { "maxItemBytes must not be negative, was $maxItemBytes" }
        require(limit >= 0) { "limit must not be negative, was $limit" }
        if (!conditions.wifiConnected || !conditions.charging) return emptyList()
        return items
            .asSequence()
            .filter { it.localPath == null }
            .filter { it.sync == SyncState.Synced && it.remoteKey != null }
            .filter { it.sizeBytes <= maxItemBytes }
            .sortedByDescending(Material::updatedAt)
            .take(limit)
            .toList()
    }

    public fun exportCopy(
        material: Material,
        destination: ExportDestination,
    ): MaterialExportPlan {
        require(material.localPath != null) { "material '${material.id}' is not cached yet" }
        return MaterialExportPlan(
            materialId = material.id,
            displayName = material.displayName,
            mimeType = material.mimeType,
            sizeBytes = material.sizeBytes,
            destination = destination,
        )
    }

    private fun cleanupPlan(
        items: List<CachedMaterial>,
        maxBytes: Long,
    ): CacheCleanupPlan {
        val byId = items.associateBy { it.material.id }
        val plan =
            CachePlanner.planEviction(
                entries =
                    items
                        .filter { it.material.localPath != null }
                        .map {
                            CacheEntry(
                                materialId = it.material.id,
                                sizeBytes = it.material.sizeBytes,
                                lastAccessedAt = it.lastAccessedAt,
                                pinned = it.material.pinnedForOffline,
                                uploaded = it.material.isSynced,
                            )
                        },
                maxBytes = maxBytes,
            )
        return CacheCleanupPlan(
            delete =
                plan.evict.map { entry ->
                    val item = byId.getValue(entry.materialId)
                    CacheCleanupItem(
                        materialId = entry.materialId,
                        localPath = item.material.localPath ?: error("planned a non-cached item for deletion"),
                        sizeBytes = entry.sizeBytes,
                    )
                },
            retainedBytes = plan.retainedBytes,
            shortfallBytes = plan.shortfallBytes,
            protectedBytes = plan.protectedBytes,
        )
    }

    private val Material.isSynced: Boolean get() = sync == SyncState.Synced

    private const val DEFAULT_LARGEST_LIMIT = 10
    private const val DEFAULT_PREFETCH_LIMIT = 20
    private const val DEFAULT_PREFETCH_MAX_ITEM_BYTES = 25L * 1024L * 1024L
}

public data class CachedMaterial(
    val material: Material,
    val lastAccessedAt: Instant,
)

public data class CacheUsage(
    val totalBytes: Long,
    val pinnedBytes: Long,
    val unsyncedBytes: Long,
    val evictableBytes: Long,
    val byKind: Map<MaterialKind, Long>,
    val largestItems: List<CachedMaterial>,
)

public data class CacheCleanupPlan(
    val delete: List<CacheCleanupItem>,
    val retainedBytes: Long,
    val shortfallBytes: Long = 0,
    val protectedBytes: Long = 0,
) {
    public val reclaimedBytes: Long get() = delete.sumOf { it.sizeBytes }
    public val isOverBudget: Boolean get() = shortfallBytes > 0
}

public data class CacheCleanupItem(
    val materialId: String,
    val localPath: String,
    val sizeBytes: Long,
)

public data class PrefetchConditions(
    val wifiConnected: Boolean,
    val charging: Boolean,
)

public sealed interface ExportDestination {
    public data class Downloads(
        val displayName: String,
    ) : ExportDestination {
        init {
            require(displayName.isNotBlank()) { "displayName must not be blank" }
        }
    }

    public data class SafUri(
        val uri: String,
    ) : ExportDestination {
        init {
            require(uri.isNotBlank()) { "uri must not be blank" }
        }
    }
}

/** A request a platform adapter can execute without exposing the app-private source path to UI. */
public data class MaterialExportPlan(
    val materialId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val destination: ExportDestination,
)
