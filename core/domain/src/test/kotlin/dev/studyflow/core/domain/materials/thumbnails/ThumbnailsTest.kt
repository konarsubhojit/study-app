package dev.studyflow.core.domain.materials.thumbnails

import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.MaterialKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@DisplayName("Thumbnails")
class ThumbnailsTest {
    private val hash = ContentHash("a".repeat(64))

    @Test
    fun `key is derived from the content hash and the size`() {
        assertEquals(
            "${hash.hex}@256",
            ThumbnailKey.of(hash, ThumbnailSpec.GRID).value,
            "the grid key is the digest and the requested edge",
        )
    }

    @Test
    fun `identical content shares one key`() {
        assertEquals(
            ThumbnailKey.of(hash, ThumbnailSpec.GRID),
            ThumbnailKey.of(ContentHash("a".repeat(64)), ThumbnailSpec.GRID),
            "the same bytes must never be rendered under two keys",
        )
    }

    @Test
    fun `different sizes do not share a key`() {
        assertNotEquals(
            ThumbnailKey.of(hash, ThumbnailSpec.GRID),
            ThumbnailKey.of(hash, ThumbnailSpec(maxEdgePx = 512)),
            "a larger request must not be served the grid bitmap",
        )
    }

    @Test
    fun `a size of zero is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ThumbnailSpec(maxEdgePx = 0) }
    }

    @Test
    fun `the source classifies itself from its mime type`() {
        val source = ThumbnailSource(localPath = "/files/materials/m1", mimeType = "application/pdf")
        assertEquals(MaterialKind.PDF, source.kind, "a PDF must render as a PDF")
    }

    @Test
    fun `placeholder colours are stable for the same digest`() {
        assertEquals(
            ThumbnailPlaceholder.of(hash),
            ThumbnailPlaceholder.of(ContentHash("a".repeat(64))),
            "a scrolling grid must not flicker through a different placeholder per recomposition",
        )
    }

    @Test
    fun `placeholder is fully opaque and shaded towards the bottom`() {
        val placeholder = ThumbnailPlaceholder.of(ContentHash("7f" + "0".repeat(62)))
        assertEquals(0xFF, placeholder.topColor ushr 24 and 0xFF, "a placeholder must be opaque")
        assertEquals(0xFF, placeholder.bottomColor ushr 24 and 0xFF, "a placeholder must be opaque")
        assertTrue(
            (placeholder.bottomColor and 0xFF) < (placeholder.topColor and 0xFF),
            "the bottom colour is the darker of the pair",
        )
    }

    @Test
    fun `the placeholder label contrasts with the gradient it is drawn on`() {
        val placeholder = ThumbnailPlaceholder.of(ContentHash("7f" + "0".repeat(62)))
        val backgroundLuminance = perceivedLuminance(placeholder.bottomColor)
        val labelLuminance = perceivedLuminance(placeholder.labelColor)

        assertTrue(
            kotlin.math.abs(labelLuminance - backgroundLuminance) > 80,
            "a badge the user cannot read is no better than an empty cell",
        )
    }

    @Test
    fun `a cache within quota evicts nothing`() {
        val entries = listOf(entry("a", sizeBytes = 10), entry("b", sizeBytes = 10))

        assertEquals(
            emptyList<ThumbnailCacheEntry>(),
            ThumbnailCachePolicy.planEviction(entries, maxBytes = 100),
            "nothing is evicted while the cache fits its quota",
        )
    }

    @Test
    fun `an over-quota cache evicts least recently used first`() {
        val newest = entry("new", sizeBytes = 40, lastAccessedAt = "2026-03-01T09:00:00Z")
        val oldest = entry("old", sizeBytes = 40, lastAccessedAt = "2026-01-01T09:00:00Z")

        val evicted = ThumbnailCachePolicy.planEviction(listOf(newest, oldest), maxBytes = 50)

        assertEquals(listOf(oldest), evicted, "the stalest thumbnail goes first")
    }

    @Test
    fun `every thumbnail is evictable so the quota is always reachable`() {
        val entries =
            List(4) { index ->
                entry("k$index", sizeBytes = 10, lastAccessedAt = "2026-0${index + 1}-01T09:00:00Z")
            }

        val evicted = ThumbnailCachePolicy.planEviction(entries, maxBytes = 0)

        assertEquals(
            40L,
            evicted.sumOf(ThumbnailCacheEntry::sizeBytes),
            "thumbnails are regenerable, so none of them is protected from eviction",
        )
    }

    private fun entry(
        key: String,
        sizeBytes: Long,
        lastAccessedAt: String = "2026-03-01T09:00:00Z",
    ): ThumbnailCacheEntry =
        ThumbnailCacheEntry(
            key = ThumbnailKey(key),
            sizeBytes = sizeBytes,
            lastAccessedAt = Instant.parse(lastAccessedAt),
        )

    private fun perceivedLuminance(color: Int): Int {
        val red = (color ushr 16) and 0xFF
        val green = (color ushr 8) and 0xFF
        val blue = color and 0xFF
        return (299 * red + 587 * green + 114 * blue) / 1_000
    }
}
