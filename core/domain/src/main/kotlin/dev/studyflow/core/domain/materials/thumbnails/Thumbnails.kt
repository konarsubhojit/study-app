package dev.studyflow.core.domain.materials.thumbnails

import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.MaterialKind

/**
 * How large a thumbnail should be (issue #42).
 *
 * The size is part of the cache key rather than a rendering detail: a grid cell and a detail
 * header want different pixel budgets, and a key that ignored the size would hand one of them the
 * other's bitmap.
 *
 * @property maxEdgePx longest edge of the rendered bitmap, in pixels; the aspect ratio of the
 *   source is preserved, so this is a bounding box rather than an exact size.
 */
public data class ThumbnailSpec(
    val maxEdgePx: Int,
) {
    init {
        require(maxEdgePx in 1..MAX_EDGE_PX) { "ThumbnailSpec.maxEdgePx must be in 1..$MAX_EDGE_PX, was $maxEdgePx" }
    }

    public companion object {
        /** Beyond this a "thumbnail" is just a second copy of the original, which defeats the point. */
        private const val MAX_EDGE_PX = 2048

        /** The catalogue grid: big enough for a dense phone grid, small enough to decode instantly. */
        public val GRID: ThumbnailSpec = ThumbnailSpec(maxEdgePx = 256)
    }
}

/**
 * The content-addressed identity of one thumbnail.
 *
 * Deriving the key from the material's SHA-256 rather than from its id is what makes thumbnails
 * deduplicate for free: the same lecture slide imported twice, or shared between two folders, is
 * rendered once and read from cache every time after that.
 */
@JvmInline
public value class ThumbnailKey(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ThumbnailKey must not be blank" }
    }

    override fun toString(): String = value

    public companion object {
        /** The only key shape the app mints: `<sha256>@<edge>`. */
        public fun of(
            contentHash: ContentHash,
            spec: ThumbnailSpec,
        ): ThumbnailKey = ThumbnailKey("${contentHash.hex}@${spec.maxEdgePx}")
    }
}

/**
 * The local file a thumbnail can be rendered from.
 *
 * The path crosses this boundary as a plain string, the same way import URIs do, so that
 * `:core:domain` keeps no Android opinion about where the bytes live.
 */
public data class ThumbnailSource(
    val localPath: String,
    val mimeType: String,
    val displayName: String = "",
) {
    init {
        require(localPath.isNotBlank()) { "ThumbnailSource.localPath must not be blank" }
    }

    /** The category the renderer dispatches on. */
    public val kind: MaterialKind get() = MaterialKind.of(mimeType, displayName)
}

/**
 * A stable stand-in drawn while a thumbnail is being read or rendered (issue #42).
 *
 * A blur-hash would be prettier, but it has to be produced by whoever rendered the image and then
 * stored somewhere, which means a schema change and a null column for every material whose
 * thumbnail has not been generated yet. Deriving two muted colours from the digest costs nothing,
 * needs no storage, and gives the property that actually matters during a scroll: the same file
 * always shows the same placeholder, so the grid does not flicker through a different colour every
 * time a cell is recycled.
 *
 * Colours are ARGB integers so the value stays Android-free; the UI layer wraps them in `Color`.
 *
 * @property labelColor what to draw *on* the gradient. Derived from the gradient's own luminance
 *   rather than from the theme, because the gradient does not change with the theme: a fixed
 *   on-surface colour would be legible in one theme and invisible in the other.
 */
public data class ThumbnailPlaceholder(
    val topColor: Int,
    val bottomColor: Int,
    val labelColor: Int,
) {
    public companion object {
        /** Muted, low-chroma surfaces: a placeholder must never compete with the real thumbnail. */
        private val PALETTE: List<Int> =
            listOf(
                0xFF9E8CA8.toInt(),
                0xFF8CA89E.toInt(),
                0xFFA89E8C.toInt(),
                0xFF8C9EA8.toInt(),
                0xFFA88C9E.toInt(),
                0xFF9EA88C.toInt(),
                0xFF9E9EA8.toInt(),
                0xFFA89E9E.toInt(),
            )

        private const val PALETTE_INDEX_HEX_LENGTH = 2
        private const val HEX_RADIX = 16
        private const val SHADE_NUMERATOR = 4
        private const val SHADE_DENOMINATOR = 5
        private const val CHANNEL_MASK = 0xFF
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private val ALPHA_MASK = 0xFF000000.toInt()

        // ITU-R BT.601 weights, scaled to integers so no floating point is needed.
        private const val RED_WEIGHT = 299
        private const val GREEN_WEIGHT = 587
        private const val BLUE_WEIGHT = 114
        private const val WEIGHT_TOTAL = 1_000
        private const val MID_LUMINANCE = 140

        /** Near-black and near-white: contrast against a mid-tone gradient, without the harshness. */
        private val INK = 0xFF1A1A1A.toInt()
        private val PAPER = 0xFFFAFAFA.toInt()

        /** The placeholder for [contentHash]; the same digest always yields the same colours. */
        public fun of(contentHash: ContentHash): ThumbnailPlaceholder {
            val index = contentHash.hex.take(PALETTE_INDEX_HEX_LENGTH).toInt(HEX_RADIX) % PALETTE.size
            val top = PALETTE[index]
            val bottom = top.shaded()
            return ThumbnailPlaceholder(
                topColor = top,
                bottomColor = bottom,
                labelColor = if (bottom.isLight()) INK else PAPER,
            )
        }

        /** Perceived brightness, which is what decides whether ink or paper reads better on it. */
        private fun Int.isLight(): Boolean {
            val red = (this ushr RED_SHIFT) and CHANNEL_MASK
            val green = (this ushr GREEN_SHIFT) and CHANNEL_MASK
            val blue = this and CHANNEL_MASK
            val luminance = (RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * blue) / WEIGHT_TOTAL
            return luminance > MID_LUMINANCE
        }

        private fun Int.shaded(): Int {
            val red = (this ushr RED_SHIFT) and CHANNEL_MASK
            val green = (this ushr GREEN_SHIFT) and CHANNEL_MASK
            val blue = this and CHANNEL_MASK
            return ALPHA_MASK or
                (red.darken() shl RED_SHIFT) or
                (green.darken() shl GREEN_SHIFT) or
                blue.darken()
        }

        private fun Int.darken(): Int = this * SHADE_NUMERATOR / SHADE_DENOMINATOR
    }
}
