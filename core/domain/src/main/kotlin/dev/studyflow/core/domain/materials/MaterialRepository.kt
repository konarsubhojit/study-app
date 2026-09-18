package dev.studyflow.core.domain.materials

import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import kotlinx.coroutines.flow.Flow

/**
 * The materials catalogue, observed and written to (issue #37).
 *
 * [findByContentHash] is what makes deduplication possible: a freshly streamed-and-hashed import
 * can ask "do I already have this?" before it ever creates a second catalogue row or queues a
 * second upload, however many times the same file gets shared or picked again.
 */
public interface MaterialRepository {
    /** Every material in the catalogue, newest first. */
    public fun observeAll(): Flow<List<Material>>

    /** Materials in one folder, or the top level when [folderId] is `null`. */
    public fun observeInFolder(folderId: String?): Flow<List<Material>>

    /** The single material with [id], or `null` once it no longer exists (deleted or never did). */
    public fun observeById(id: String): Flow<Material?>

    /** The catalogue entry already stored under [contentHash], if any. */
    public suspend fun findByContentHash(contentHash: ContentHash): Material?

    /** Inserts or replaces a catalogue entry. */
    public suspend fun save(material: Material)

    /** Persists lightweight preview progress so reopening a material resumes where the user left. */
    public suspend fun updatePreviewState(
        id: String,
        pageIndex: Int?,
        positionMillis: Long?,
        playbackSpeed: Float?,
    )

    /** Soft-deletes a material while preserving the tombstone for sync. */
    public suspend fun delete(id: String)
}
