package dev.studyflow.core.domain.materials.thumbnails

import dev.studyflow.core.domain.materials.CacheEntry
import dev.studyflow.core.domain.materials.CachePlanner
import dev.studyflow.core.model.MaterialKind
import kotlin.time.Instant

/**
 * Renders a small bitmap from a file the device can decode itself (issue #42).
 *
 * Implementations are platform code — image decoders, a frame grabber, a PDF page rasteriser — and
 * are expected to be honest about what they cannot do: a format this device cannot render returns
 * `null` rather than throwing, so the catalogue falls back to the placeholder and, once the server
 * grows one, to the server-side thumbnail tracked in issue #7.
 */
public interface ThumbnailRenderer {
    /** Whether [kind] is one this renderer can rasterise at all; checked before any file is opened. */
    public fun supports(kind: MaterialKind): Boolean

    /**
     * Renders [source] into encoded image bytes no larger than [spec], or `null` when this device
     * cannot render it.
     *
     * Implementations must be cancellable: a grid scrolls faster than a video frame decodes, and
     * abandoned work has to stop rather than finish into a cache nobody is waiting on.
     */
    public suspend fun render(
        source: ThumbnailSource,
        spec: ThumbnailSpec,
    ): ByteArray?
}

/**
 * Where rendered thumbnails are kept between scrolls and app launches.
 *
 * Separate from the material cache on purpose: thumbnails are small, numerous and always
 * regenerable, so they get their own small quota ([ThumbnailCachePolicy.DEFAULT_QUOTA_BYTES])
 * instead of competing with the originals — which are not regenerable while an upload is pending
 * and must never be evicted to make room for a picture of themselves.
 */
public interface ThumbnailCache {
    /** The cached bytes for [key], or `null` when nothing has been rendered for it yet. */
    public suspend fun read(key: ThumbnailKey): ByteArray?

    /** Stores [bytes] under [key], evicting older entries if that pushes the cache over quota. */
    public suspend fun write(
        key: ThumbnailKey,
        bytes: ByteArray,
    )

    /** Drops every cached thumbnail; used when the user clears the app's caches. */
    public suspend fun clear()
}

/**
 * One cached thumbnail, as seen by the eviction policy.
 */
public data class ThumbnailCacheEntry(
    val key: ThumbnailKey,
    val sizeBytes: Long,
    val lastAccessedAt: Instant,
) {
    init {
        require(sizeBytes >= 0) { "ThumbnailCacheEntry.sizeBytes must not be negative, was $sizeBytes" }
    }
}

/**
 * The thumbnail cache's own, small budget.
 *
 * Eviction is plain least-recently-used because — unlike the material cache, where a pinned or
 * not-yet-uploaded file is the only copy of the user's data ([CachePlanner]) — every thumbnail can
 * be rendered again from the original. Nothing here is ever protected, so the cache can always be
 * brought back inside its quota.
 */
public object ThumbnailCachePolicy {
    /** A few thousand grid thumbnails: generous for browsing, invisible next to the originals. */
    public const val DEFAULT_QUOTA_BYTES: Long = 32L * 1024 * 1024

    /** Chooses the thumbnails to delete so the cache fits within [maxBytes], least-recent first. */
    public fun planEviction(
        entries: List<ThumbnailCacheEntry>,
        maxBytes: Long = DEFAULT_QUOTA_BYTES,
    ): List<ThumbnailCacheEntry> {
        val byKey = entries.associateBy { it.key.value }
        val plan =
            CachePlanner.planEviction(
                entries =
                    entries.map { entry ->
                        CacheEntry(
                            materialId = entry.key.value,
                            sizeBytes = entry.sizeBytes,
                            lastAccessedAt = entry.lastAccessedAt,
                        )
                    },
                maxBytes = maxBytes,
            )
        return plan.evict.mapNotNull { byKey[it.materialId] }
    }
}
