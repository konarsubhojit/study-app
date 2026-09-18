package dev.studyflow.feature.materials.data

import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailKey
import dev.studyflow.core.testing.coroutines.testDispatcherProvider
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@DisplayName("FileThumbnailCache")
class FileThumbnailCacheTest {
    @TempDir
    lateinit var cacheDir: File

    private var now: Instant = TEST_WALL_CLOCK

    @Test
    fun `a written thumbnail is read back`() =
        runTest {
            val cache = cache()
            val key = ThumbnailKey("abc@256")

            cache.write(key, byteArrayOf(1, 2, 3))

            assertArrayEquals(byteArrayOf(1, 2, 3), cache.read(key))
            assertNull(cache.read(ThumbnailKey("other@256")), "an unrendered key is a miss, not an error")
        }

    @Test
    fun `no temporary file survives a completed write`() =
        runTest {
            cache().write(ThumbnailKey("abc@256"), byteArrayOf(1))

            assertEquals(
                emptyList<String>(),
                cacheDir
                    .listFiles()
                    .orEmpty()
                    .map { file -> file.name }
                    .filter { it.endsWith(".tmp") },
                "a half-written image must never be left where it could be served as a thumbnail",
            )
        }

    @Test
    fun `the cache is trimmed back to its own quota, least recently used first`() =
        runTest {
            val cache = cache(quotaBytes = 8)

            cache.write(ThumbnailKey("old@256"), ByteArray(4))
            now += 1.hours
            cache.write(ThumbnailKey("new@256"), ByteArray(4))
            now += 1.hours
            cache.write(ThumbnailKey("newest@256"), ByteArray(4))

            assertNull(cache.read(ThumbnailKey("old@256")), "the stalest thumbnail is the one evicted")
            assertArrayEquals(ByteArray(4), cache.read(ThumbnailKey("newest@256")))
        }

    @Test
    fun `a key can never name a file outside the cache directory`() =
        runTest {
            val cache = cache()
            val key = ThumbnailKey("../../escaped")

            cache.write(key, byteArrayOf(9))

            assertArrayEquals(byteArrayOf(9), cache.read(key), "an encoded name still round-trips")
            assertTrue(
                cacheDir.listFiles().orEmpty().isNotEmpty(),
                "the bytes stay inside the cache directory the caller chose",
            )
            assertFalse(
                File(cacheDir.parentFile?.parentFile, "escaped").exists(),
                "a key must not be able to traverse out of the cache",
            )
        }

    @Test
    fun `two keys that differ only in punctuation stay two thumbnails`() =
        runTest {
            val cache = cache()

            cache.write(ThumbnailKey("a/b@256"), byteArrayOf(1))
            cache.write(ThumbnailKey("a.b@256"), byteArrayOf(2))

            assertArrayEquals(
                byteArrayOf(1),
                cache.read(ThumbnailKey("a/b@256")),
                "one material's cell must never be served another's thumbnail",
            )
            assertArrayEquals(byteArrayOf(2), cache.read(ThumbnailKey("a.b@256")))
        }

    @Test
    fun `clearing the cache removes every thumbnail`() =
        runTest {
            val cache = cache()
            cache.write(ThumbnailKey("abc@256"), byteArrayOf(1))

            cache.clear()

            assertNull(cache.read(ThumbnailKey("abc@256")))
        }

    private fun TestScope.cache(quotaBytes: Long = 1_024): FileThumbnailCache =
        FileThumbnailCache(
            directory = { cacheDir },
            dispatcherProvider = testDispatcherProvider(),
            clock = Clock { now },
            quotaBytes = quotaBytes,
        )
}
