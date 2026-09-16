package dev.studyflow.core.domain.materials

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@DisplayName("CachePlanner")
class CachePlannerTest {
    private val mib = 1024L * 1024L

    @Test
    fun `nothing is evicted while the cache is within budget`() {
        val entries = listOf(entry("a", 10 * mib, "2026-03-01T10:00:00Z"))

        val plan = CachePlanner.planEviction(entries, maxBytes = 100 * mib)

        assertTrue(plan.evict.isEmpty())
        assertFalse(plan.isOverBudget)
    }

    @Test
    fun `the least recently used file goes first`() {
        val entries =
            listOf(
                entry("recent", 60 * mib, "2026-03-05T10:00:00Z"),
                entry("stale", 60 * mib, "2026-01-01T10:00:00Z"),
            )

        val plan = CachePlanner.planEviction(entries, maxBytes = 100 * mib)

        assertEquals(listOf("stale"), plan.evict.map { it.materialId })
    }

    @Test
    fun `eviction stops as soon as the cache fits`() {
        val entries =
            listOf(
                entry("oldest", 30 * mib, "2026-01-01T10:00:00Z"),
                entry("older", 30 * mib, "2026-02-01T10:00:00Z"),
                entry("newest", 30 * mib, "2026-03-01T10:00:00Z"),
            )

        val plan = CachePlanner.planEviction(entries, maxBytes = 70 * mib)

        assertEquals(listOf("oldest"), plan.evict.map { it.materialId })
        assertEquals(60 * mib, plan.retainedBytes)
    }

    @Test
    fun `pinned files are never evicted`() {
        val entries =
            listOf(
                entry("pinned", 80 * mib, "2026-01-01T10:00:00Z", pinned = true),
                entry("unpinned", 40 * mib, "2026-03-01T10:00:00Z"),
            )

        val plan = CachePlanner.planEviction(entries, maxBytes = 100 * mib)

        assertEquals(listOf("unpinned"), plan.evict.map { it.materialId })
    }

    @Test
    fun `files that have not finished uploading are never evicted`() {
        val entries =
            listOf(
                entry("not-yet-uploaded", 80 * mib, "2026-01-01T10:00:00Z", uploaded = false),
                entry("safe-copy", 40 * mib, "2026-03-01T10:00:00Z"),
            )

        val plan = CachePlanner.planEviction(entries, maxBytes = 100 * mib)

        assertEquals(
            listOf("safe-copy"),
            plan.evict.map { it.materialId },
            "evicting a pending upload would destroy the only copy of the user's file",
        )
    }

    @Test
    fun `an unreachable budget is reported rather than silently exceeded`() {
        val entries =
            listOf(
                entry("pinned-1", 80 * mib, "2026-01-01T10:00:00Z", pinned = true),
                entry("pinned-2", 80 * mib, "2026-01-02T10:00:00Z", pinned = true),
                entry("evictable", 10 * mib, "2026-01-03T10:00:00Z"),
            )

        val plan = CachePlanner.planEviction(entries, maxBytes = 100 * mib)

        assertEquals(listOf("evictable"), plan.evict.map { it.materialId })
        assertTrue(plan.isOverBudget)
        assertEquals(60 * mib, plan.shortfallBytes)
        assertEquals(160 * mib, plan.protectedBytes)
    }

    @Test
    fun `among equally stale files the larger one is evicted first`() {
        val entries =
            listOf(
                entry("small", 20 * mib, "2026-01-01T10:00:00Z"),
                entry("large", 40 * mib, "2026-01-01T10:00:00Z"),
            )

        val plan = CachePlanner.planEviction(entries, maxBytes = 30 * mib)

        assertEquals(listOf("large"), plan.evict.map { it.materialId })
    }

    @Test
    fun `a zero budget evicts everything that is safe to evict`() {
        val entries =
            listOf(
                entry("a", 10 * mib, "2026-01-01T10:00:00Z"),
                entry("b", 10 * mib, "2026-01-02T10:00:00Z", pinned = true),
            )

        val plan = CachePlanner.planEviction(entries, maxBytes = 0)

        assertEquals(listOf("a"), plan.evict.map { it.materialId })
        assertEquals(10 * mib, plan.reclaimedBytes)
    }

    private fun entry(
        id: String,
        sizeBytes: Long,
        lastAccessed: String,
        pinned: Boolean = false,
        uploaded: Boolean = true,
    ) = CacheEntry(
        materialId = id,
        sizeBytes = sizeBytes,
        lastAccessedAt = Instant.parse(lastAccessed),
        pinned = pinned,
        uploaded = uploaded,
    )
}
