package dev.studyflow.feature.materials.data

import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailCache
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailCacheEntry
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailCachePolicy
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailKey
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.time.Instant

/**
 * The thumbnail cache on disk, with its own small quota (issue #42).
 *
 * Deliberately *not* the material cache: an original that has not finished uploading is the only
 * copy of the user's data, while a thumbnail can always be rendered again. Keeping them apart is
 * what lets this one be evicted freely — [ThumbnailCachePolicy] decides what goes — without the
 * eviction rules of the material cache having to reason about two kinds of file at once.
 *
 * Writes go to a temporary file that is renamed into place, so a process death mid-write leaves no
 * half-written JPEG to be served as a thumbnail later.
 *
 * @param directory where the cached files live; created on demand.
 * @param clock reads the wall clock a read stamps an entry with, so eviction order is testable.
 * @param quotaBytes the budget this cache is trimmed back to after every write.
 */
public class FileThumbnailCache(
    private val directory: () -> File,
    private val dispatcherProvider: DispatcherProvider,
    private val clock: Clock,
    private val quotaBytes: Long = ThumbnailCachePolicy.DEFAULT_QUOTA_BYTES,
) : ThumbnailCache {
    override suspend fun read(key: ThumbnailKey): ByteArray? =
        withContext(dispatcherProvider.io) {
            val file = fileFor(key)
            if (!file.isFile) return@withContext null
            try {
                val bytes = file.readBytes()
                // Last-modified doubles as last-accessed, which is what makes eviction
                // least-recently-*used* rather than least-recently-written.
                file.setLastModified(clock.now().toEpochMilliseconds())
                bytes
            } catch (_: IOException) {
                null
            }
        }

    override suspend fun write(
        key: ThumbnailKey,
        bytes: ByteArray,
    ) {
        withContext(dispatcherProvider.io) {
            val file = fileFor(key)
            try {
                val staging = File(file.parentFile, "${file.name}.tmp")
                staging.writeBytes(bytes)
                if (staging.renameTo(file)) {
                    file.setLastModified(clock.now().toEpochMilliseconds())
                } else {
                    staging.delete()
                }
            } catch (_: IOException) {
                // A cache is an optimisation; a full or unwritable disk costs a re-render, not a
                // failed import or a crashed grid.
                return@withContext
            }
            trimToQuota()
        }
    }

    override suspend fun clear() {
        withContext(dispatcherProvider.io) {
            directory().listFiles()?.forEach { file -> file.delete() }
        }
    }

    private fun trimToQuota() {
        val files = directory().listFiles()?.filter { file -> file.isFile }.orEmpty()
        val entries =
            files.map { file ->
                ThumbnailCacheEntry(
                    key = ThumbnailKey(file.name),
                    sizeBytes = file.length(),
                    lastAccessedAt = Instant.fromEpochMilliseconds(file.lastModified()),
                )
            }
        val byName = files.associateBy { file -> file.name }
        ThumbnailCachePolicy.planEviction(entries, quotaBytes).forEach { entry ->
            byName[entry.key.value]?.delete()
        }
    }

    /**
     * The file a key maps to.
     *
     * The name is filtered rather than trusted: a key is derived from a digest today, but a cache
     * that turns an arbitrary string into a path is one refactor away from writing outside its own
     * directory.
     */
    private fun fileFor(key: ThumbnailKey): File {
        val safeName = key.value.map { character -> if (character in SAFE_NAME_CHARACTERS) character else '_' }
        return File(directory().apply { mkdirs() }, safeName.joinToString(separator = ""))
    }

    private companion object {
        val SAFE_NAME_CHARACTERS: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('@', '-', '_')).toSet()
    }
}
