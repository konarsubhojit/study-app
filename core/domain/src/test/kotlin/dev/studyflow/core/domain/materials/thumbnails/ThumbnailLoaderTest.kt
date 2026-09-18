package dev.studyflow.core.domain.materials.thumbnails

import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.MaterialKind
import dev.studyflow.core.testing.coroutines.testDispatcherProvider
import dev.studyflow.core.testing.data.FakeThumbnailCache
import dev.studyflow.core.testing.data.FakeThumbnailRenderer
import dev.studyflow.core.testing.data.testMaterial
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ThumbnailLoader")
class ThumbnailLoaderTest {
    private val cache = FakeThumbnailCache()

    @Test
    fun `a rendered thumbnail is cached under its content-addressed key`() =
        runTest {
            val renderer = FakeThumbnailRenderer(bytes = byteArrayOf(7, 7))
            val material = testMaterial(localUri = "/files/materials/m1")

            val bytes = loader(renderer).load(material)

            assertArrayEquals(byteArrayOf(7, 7), bytes, "the rendered bytes are what the grid draws")
            assertEquals(
                listOf(ThumbnailKey.of(material.contentHash, ThumbnailSpec.GRID)),
                cache.writes,
                "a thumbnail is stored under the digest of the file it came from",
            )
        }

    @Test
    fun `identical content is rendered once however many catalogue rows hold it`() =
        runTest {
            val renderer = FakeThumbnailRenderer(bytes = byteArrayOf(1))
            val loader = loader(renderer)
            val hash = ContentHash("b".repeat(64))

            loader.load(testMaterial(id = "m1", contentHash = hash, localUri = "/files/materials/m1"))
            loader.load(testMaterial(id = "m2", contentHash = hash, localUri = "/files/materials/m2"))

            assertEquals(1, renderer.rendered.size, "the second copy of the same bytes is a cache hit")
        }

    @Test
    fun `a cache hit never opens the file`() =
        runTest {
            val material = testMaterial(localUri = "/files/materials/m1")
            cache.put(ThumbnailKey.of(material.contentHash, ThumbnailSpec.GRID), byteArrayOf(9))
            val renderer = FakeThumbnailRenderer()

            val bytes = loader(renderer).load(material)

            assertArrayEquals(byteArrayOf(9), bytes, "the cached bytes are returned as they are")
            assertTrue(renderer.rendered.isEmpty(), "browsing must not re-render what is already cached")
        }

    @Test
    fun `a material with no local copy is not downloaded to be browsed`() =
        runTest {
            val renderer = FakeThumbnailRenderer()

            val bytes = loader(renderer).load(testMaterial(localUri = null))

            assertNull(bytes, "the caller falls back to the placeholder instead")
            assertTrue(renderer.rendered.isEmpty(), "no original is fetched just to fill a grid cell")
        }

    @Test
    fun `a format this device cannot render resolves to no thumbnail`() =
        runTest {
            val renderer = FakeThumbnailRenderer(supported = setOf(MaterialKind.IMAGE))
            val material =
                testMaterial(
                    mimeType = "application/zip",
                    displayName = "past-papers.zip",
                    localUri = "/files/materials/m1",
                )

            assertNull(loader(renderer).load(material), "an unrenderable format falls back to the placeholder")
            assertTrue(cache.writes.isEmpty(), "nothing is cached for a format that was never rendered")
        }

    @Test
    fun `an abandoned render is cancelled and caches nothing`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val renderer =
                FakeThumbnailRenderer(
                    onRender = {
                        started.complete(Unit)
                        awaitCancellation()
                    },
                )
            val job = launch { loader(renderer).load(testMaterial(localUri = "/files/materials/m1")) }

            started.await()
            job.cancelAndJoin()

            assertTrue(cache.writes.isEmpty(), "scrolling away from a cell must not leave work behind")
        }

    private fun TestScope.loader(renderer: FakeThumbnailRenderer): ThumbnailLoader =
        ThumbnailLoader(cache = cache, renderer = renderer, dispatcherProvider = testDispatcherProvider())
}
