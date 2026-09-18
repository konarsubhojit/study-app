package dev.studyflow.core.domain.materials.thumbnails

import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.model.Material
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Turns a catalogue row into the small image the grid draws (issue #42).
 *
 * Three properties matter more than anything this class does with bytes:
 *
 * 1. **Browsing never downloads an original.** The only inputs are the cache and a *local* file, so
 *    a catalogue of hundred-megabyte recordings costs a few kilobytes per cell to browse. A
 *    material with no local copy resolves to `null` and the caller draws
 *    [ThumbnailPlaceholder.of] instead; fetching a remote thumbnail for it is issue #7's job, not a silent
 *    download of the original.
 * 2. **Rendering happens off the main thread.** Every call is wrapped in [DispatcherProvider.io] —
 *    decoding a PDF page on the UI thread is a dropped frame at best.
 * 3. **Abandoned work stops.** The function is a plain `suspend fun`, so a caller that scrolls a
 *    cell out of composition cancels it; the cancellation points around the render make sure that
 *    lands between the expensive steps rather than only at the end.
 *
 * Cached entries are keyed by [ThumbnailKey], which is derived from the content hash — so two
 * catalogue rows holding the same bytes are rendered once, however they arrived.
 */
public class ThumbnailLoader(
    private val cache: ThumbnailCache,
    private val renderer: ThumbnailRenderer,
    private val dispatcherProvider: DispatcherProvider,
    private val spec: ThumbnailSpec = ThumbnailSpec.GRID,
) {
    /**
     * The encoded thumbnail for [material], or `null` when this device cannot produce one.
     *
     * @throws kotlinx.coroutines.CancellationException if the caller is cancelled mid-render.
     */
    public suspend fun load(material: Material): ByteArray? =
        withContext(dispatcherProvider.io) {
            val key = ThumbnailKey.of(material.contentHash, spec)
            cache.read(key)?.let { return@withContext it }

            val localPath = material.localPath ?: return@withContext null
            val source = ThumbnailSource(localPath, material.mimeType, material.displayName)
            if (!renderer.supports(source.kind)) return@withContext null

            currentCoroutineContext().ensureActive()
            val rendered = renderer.render(source, spec) ?: return@withContext null

            // Written for every render that finished, so the next time the cell scrolls back into
            // view it is a cache hit rather than a second decode of the same file.
            cache.write(key, rendered)
            rendered
        }

    /** The colours to draw while [load] is still running, or when it returned `null`. */
    public fun placeholderFor(material: Material): ThumbnailPlaceholder = ThumbnailPlaceholder.of(material.contentHash)
}
