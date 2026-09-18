package dev.studyflow.core.domain.materials

import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.MaterialKind
import dev.studyflow.core.model.SyncState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@DisplayName("MaterialCacheManager")
class MaterialCacheManagerTest {
    private val mib = 1024L * 1024L

    @Test
    fun `usage separates cached pinned and unsynced bytes by type`() {
        val items =
            listOf(
                cached(material("pdf", "slides.pdf", 20 * mib, pinned = true)),
                cached(material("video", "lecture.mp4", 80 * mib, mimeType = "video/mp4")),
                cached(material("draft", "draft.docx", 5 * mib, sync = SyncState.Pending)),
                cached(material("remote", "remote.pdf", 10 * mib).copy(localPath = null)),
            )

        val usage = MaterialCacheManager.usage(items, largestLimit = 2)

        assertEquals(105 * mib, usage.totalBytes)
        assertEquals(20 * mib, usage.pinnedBytes)
        assertEquals(5 * mib, usage.unsyncedBytes)
        assertEquals(80 * mib, usage.evictableBytes)
        assertEquals(25 * mib, usage.byKind.getValue(MaterialKind.PDF))
        assertEquals(80 * mib, usage.byKind.getValue(MaterialKind.VIDEO))
        assertEquals(listOf("video", "pdf"), usage.largestItems.map { it.material.id })
    }

    @Test
    fun `quota cleanup deletes cold synced unpinned files and reports protected shortfall`() {
        val items =
            listOf(
                cached(material("old", "old.pdf", 70 * mib), accessed = "2026-01-01T00:00:00Z"),
                cached(material("new", "new.pdf", 60 * mib), accessed = "2026-03-01T00:00:00Z"),
                cached(material("pinned", "pin.pdf", 50 * mib, pinned = true), accessed = "2025-01-01T00:00:00Z"),
                cached(material("unsynced", "draft.pdf", 50 * mib, sync = SyncState.Pending), accessed = "2025-02-01T00:00:00Z"),
            )

        val plan = MaterialCacheManager.planQuotaCleanup(items, quotaBytes = 80 * mib)

        assertEquals(listOf("old", "new"), plan.delete.map { it.materialId })
        assertTrue(plan.isOverBudget)
        assertEquals(20 * mib, plan.shortfallBytes)
        assertEquals(100 * mib, plan.protectedBytes)
    }

    @Test
    fun `free up space never deletes pinned or unsynced local-only files`() {
        val items =
            listOf(
                cached(material("safe", "safe.pdf", 10 * mib)),
                cached(material("pinned", "pin.pdf", 10 * mib, pinned = true)),
                cached(material("unsynced", "draft.pdf", 10 * mib, sync = SyncState.Pending)),
            )

        val plan = MaterialCacheManager.planFreeUpSpace(items)

        assertEquals(listOf("safe"), plan.delete.map { it.materialId })
        assertFalse(plan.isOverBudget)
        assertEquals(10 * mib, plan.reclaimedBytes)
    }

    @Test
    fun `prefetch only picks small recent remote items on wifi and charging`() {
        val items =
            listOf(
                material("old", "old.pdf", 2 * mib, updatedAt = "2026-01-01T00:00:00Z").copy(localPath = null),
                material("recent", "recent.pdf", 2 * mib, updatedAt = "2026-03-01T00:00:00Z").copy(localPath = null),
                material("large", "large.pdf", 30 * mib, updatedAt = "2026-04-01T00:00:00Z").copy(localPath = null),
                material("cached", "cached.pdf", 1 * mib),
            )

        val candidates =
            MaterialCacheManager.prefetchCandidates(
                items,
                conditions = PrefetchConditions(wifiConnected = true, charging = true),
                maxItemBytes = 5 * mib,
            )

        assertEquals(listOf("recent", "old"), candidates.map(Material::id))
        assertTrue(
            MaterialCacheManager
                .prefetchCandidates(items, PrefetchConditions(wifiConnected = false, charging = true))
                .isEmpty(),
        )
    }

    @Test
    fun `export plan names only the public destination and not the private cached path`() {
        val material = material("m1", "exam.pdf", 2 * mib).copy(localPath = "/data/user/0/app/files/materials/exam.pdf")

        val plan = MaterialCacheManager.exportCopy(material, ExportDestination.Downloads("exam.pdf"))

        assertEquals("m1", plan.materialId)
        assertEquals("exam.pdf", plan.displayName)
        assertEquals(ExportDestination.Downloads("exam.pdf"), plan.destination)
        assertFalse(plan.toString().contains("/data/user/0/app/files/materials"))
    }

    @Test
    fun `uncached material cannot be exported`() {
        val material = material("remote", "remote.pdf", 2 * mib).copy(localPath = null)

        assertThrows(IllegalArgumentException::class.java) {
            MaterialCacheManager.exportCopy(material, ExportDestination.Downloads("remote.pdf"))
        }
    }

    private fun cached(
        material: Material,
        accessed: String = "2026-02-01T00:00:00Z",
    ): CachedMaterial = CachedMaterial(material, Instant.parse(accessed))

    private fun material(
        id: String,
        displayName: String,
        sizeBytes: Long,
        mimeType: String = "application/pdf",
        pinned: Boolean = false,
        sync: SyncState = SyncState.Synced,
        updatedAt: String = "2026-02-01T00:00:00Z",
    ): Material =
        Material(
            id = id,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            contentHash = ContentHash("a".repeat(64)),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse(updatedAt),
            remoteKey = "materials/$id",
            sync = sync,
            localPath = "/private/$id",
            pinnedForOffline = pinned,
        )
}
