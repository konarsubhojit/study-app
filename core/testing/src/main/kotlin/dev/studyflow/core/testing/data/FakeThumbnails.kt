package dev.studyflow.core.testing.data

import dev.studyflow.core.domain.materials.thumbnails.ThumbnailCache
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailKey
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailRenderer
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailSource
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailSpec
import dev.studyflow.core.model.MaterialKind

/** In-memory [ThumbnailCache] that records every read, so a test can assert on cache hits. */
public class FakeThumbnailCache : ThumbnailCache {
    private val entries: MutableMap<String, ByteArray> = mutableMapOf()

    /** Keys asked for, in order. */
    public val reads: MutableList<ThumbnailKey> = mutableListOf()

    /** Keys written, in order. */
    public val writes: MutableList<ThumbnailKey> = mutableListOf()

    /** Seeds a cache hit for [key]. */
    public fun put(
        key: ThumbnailKey,
        bytes: ByteArray,
    ) {
        entries[key.value] = bytes
    }

    override suspend fun read(key: ThumbnailKey): ByteArray? {
        reads += key
        return entries[key.value]
    }

    override suspend fun write(
        key: ThumbnailKey,
        bytes: ByteArray,
    ) {
        writes += key
        entries[key.value] = bytes
    }

    override suspend fun clear() {
        entries.clear()
    }
}

/**
 * [ThumbnailRenderer] that hands back fixed bytes for the kinds it is told to support, and records
 * what it was asked to render.
 *
 * @param onRender runs before each render, so a test can suspend, cancel, or fail inside it.
 */
public class FakeThumbnailRenderer(
    private val supported: Set<MaterialKind> = setOf(MaterialKind.IMAGE, MaterialKind.PDF, MaterialKind.VIDEO),
    private val bytes: ByteArray? = RENDERED_BYTES,
    private val onRender: suspend (ThumbnailSource) -> Unit = {},
) : ThumbnailRenderer {
    /** Sources rendered, in order. */
    public val rendered: MutableList<ThumbnailSource> = mutableListOf()

    override fun supports(kind: MaterialKind): Boolean = kind in supported

    override suspend fun render(
        source: ThumbnailSource,
        spec: ThumbnailSpec,
    ): ByteArray? {
        onRender(source)
        rendered += source
        return bytes
    }

    private companion object {
        /** Stand-in for an encoded bitmap; tests assert on identity, never on pixels. */
        val RENDERED_BYTES: ByteArray = byteArrayOf(1, 2)
    }
}
