package dev.studyflow.core.storage

import dev.studyflow.core.domain.materials.UploadPart
import dev.studyflow.core.domain.materials.UploadPlanner
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailSpec
import dev.studyflow.core.model.ContentHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@DisplayName("ObjectStore value types")
class ObjectStoreModelsTest {
    @Test
    fun `a key that escapes its prefix is rejected before any adapter sees it`() {
        listOf("../../etc/passwd", "materials/../../secrets", "/absolute/key", "materials\\windows", "")
            .forEach { candidate ->
                assertThrows(IllegalArgumentException::class.java, { ObjectKey(candidate) }, candidate)
            }
    }

    @Test
    fun `a material's key is its digest, so identical files share one object`() {
        val hash = ContentHash("a".repeat(64))

        assertEquals(ObjectKey("materials/${hash.hex}"), ObjectKey.ofMaterial(hash))
    }

    @Test
    fun `a thumbnail's key is derived from the original's digest and its size`() {
        val hash = ContentHash("a".repeat(64))

        assertEquals(ObjectKey("thumbnails/${hash.hex}/256"), ObjectKey.ofThumbnail(hash, ThumbnailSpec.GRID))
    }

    @Test
    fun `a presigned URL knows when it has expired`() {
        val url = PresignedUrl("https://storage.example/object?signature=x", Instant.parse("2026-03-01T09:15:00Z"))

        assertEquals(true, url.isValidAt(Instant.parse("2026-03-01T09:14:59Z")))
        assertEquals(false, url.isValidAt(Instant.parse("2026-03-01T09:15:00Z")))
    }

    @Test
    fun `a cleartext presigned URL is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PresignedUrl("http://storage.example/object?signature=x", EXPIRY)
        }
    }

    @Test
    fun `a session signs the parts the domain planner produced, in order`() {
        val hash = ContentHash("b".repeat(64))
        val plan = UploadPlanner.plan(totalBytes = 20L * 1024 * 1024, contentHash = hash)

        val session =
            UploadSession.of(
                key = ObjectKey.ofMaterial(hash),
                uploadId = "upload-1",
                plan = plan,
                expiresAt = EXPIRY,
            ) { part -> PresignedUrl("https://storage.example/part-${part.number}", EXPIRY) }

        assertEquals(plan.parts, session.parts.map { it.part })
        assertEquals(plan.totalBytes, session.sizeBytes)
    }

    @Test
    fun `a session with gaps in its part numbering is not a session`() {
        val signed =
            SignedPart(
                part = UploadPart(number = 2, offset = 0, size = 1),
                url = PresignedUrl("https://storage.example/part", EXPIRY),
            )

        assertThrows(IllegalArgumentException::class.java) {
            UploadSession(
                key = ObjectKey("materials/x"),
                uploadId = "upload-1",
                parts = listOf(signed),
                expiresAt = EXPIRY,
            )
        }
    }

    private companion object {
        val EXPIRY: Instant = Instant.parse("2026-03-01T09:15:00Z")
    }
}
