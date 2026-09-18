package dev.studyflow.feature.materials.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailRenderer
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailSource
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailSpec
import dev.studyflow.core.model.MaterialKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/**
 * The on-device half of the thumbnail pipeline (issue #42).
 *
 * Three decoders cover what a phone can render without help: the platform image decoder, a video
 * frame grabber, and the PDF rasteriser. Anything else — an office document, an archive — returns
 * `null`, which is the signal for the catalogue to keep drawing the placeholder until the
 * server-side fallback of issue #7 exists.
 *
 * Nothing here decodes a full-size bitmap first. Images are sampled down while they are read, video
 * frames are asked for at the size they are wanted, and a PDF page is rasterised straight into a
 * thumbnail-sized bitmap — so a 40-megapixel scan costs a few hundred kilobytes of heap rather than
 * 160 MB and an `OutOfMemoryError` in a scrolling grid.
 */
public class AndroidThumbnailRenderer(
    private val dispatcherProvider: DispatcherProvider,
) : ThumbnailRenderer {
    override fun supports(kind: MaterialKind): Boolean = kind in SUPPORTED_KINDS

    override suspend fun render(
        source: ThumbnailSource,
        spec: ThumbnailSpec,
    ): ByteArray? =
        withContext(dispatcherProvider.default) {
            if (!supports(source.kind)) return@withContext null
            val file = source.localPath.toLocalFileOrNull()
            if (file == null || !file.isFile) return@withContext null

            val bitmap = decode(file, source.kind, spec) ?: return@withContext null

            currentCoroutineContext().ensureActive()
            try {
                bitmap.encode()
            } finally {
                bitmap.recycle()
            }
        }

    /**
     * The decoded bitmap, or `null` for a file this device cannot read.
     *
     * Every decoder failure is a placeholder in one grid cell rather than a crash, so the whole
     * family of "this file is not what it claims to be" throwables is caught here.
     */
    private fun decode(
        file: File,
        kind: MaterialKind,
        spec: ThumbnailSpec,
    ): Bitmap? =
        try {
            when (kind) {
                MaterialKind.IMAGE -> decodeImage(file, spec)
                MaterialKind.VIDEO -> decodeVideoFrame(file, spec)
                MaterialKind.PDF -> decodePdfCover(file, spec)
                else -> null
            }
        } catch (cancellation: CancellationException) {
            // `CancellationException` is an `IllegalStateException`; a scroll must cancel the
            // render rather than be mistaken for an undecodable file.
            throw cancellation
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalStateException) {
            // What `MediaMetadataRetriever` and `PdfRenderer` throw for a file they cannot parse.
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: OutOfMemoryError) {
            // A pathological source can still exhaust the heap mid-decode; losing one thumbnail is
            // survivable, taking the process down with it is not.
            null
        }

    /** Reads the bounds first, then decodes at the smallest power-of-two sample that covers [spec]. */
    private fun decodeImage(
        file: File,
        spec: ThumbnailSpec,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, spec)
            }
        return BitmapFactory.decodeFile(file.absolutePath, options)?.scaledTo(spec)
    }

    private fun decodeVideoFrame(
        file: File,
        spec: ThumbnailSpec,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            // The first sync frame: seeking to a "representative" frame costs a decode of the
            // whole GOP, which is not worth it for a 256 px cell.
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.scaledTo(spec)
        } finally {
            retriever.release()
        }
    }

    /** Rasterises page one — the cover is what a reader recognises a document by. */
    private fun decodePdfCover(
        file: File,
        spec: ThumbnailSpec,
    ): Bitmap? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) return@use null
                renderer.openPage(0).use { page ->
                    val (width, height) = boundedSize(page.width, page.height, spec)
                    val bitmap = createBitmap(width, height)
                    // A PDF page is transparent where it is blank; a page rendered without a white
                    // canvas underneath it reads as a black rectangle in the grid.
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }

    private fun Bitmap.scaledTo(spec: ThumbnailSpec): Bitmap {
        val (targetWidth, targetHeight) = boundedSize(width, height, spec)
        if (targetWidth == width && targetHeight == height) return this
        val scaled = scale(targetWidth, targetHeight)
        if (scaled !== this) recycle()
        return scaled
    }

    private fun Bitmap.encode(): ByteArray =
        ByteArrayOutputStream().use { sink ->
            compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, sink)
            sink.toByteArray()
        }

    private fun boundedSize(
        width: Int,
        height: Int,
        spec: ThumbnailSpec,
    ): Pair<Int, Int> {
        val longestEdge = maxOf(width, height)
        if (longestEdge <= spec.maxEdgePx) return width to height
        val scale = spec.maxEdgePx.toDouble() / longestEdge
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    private fun sampleSizeFor(
        width: Int,
        height: Int,
        spec: ThumbnailSpec,
    ): Int {
        var sampleSize = 1
        while (maxOf(width, height) / (sampleSize * 2) >= spec.maxEdgePx) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * The local file behind a path, or `null` when the path is not one.
     *
     * A malformed or opaque `file:` URI is a catalogue row this device cannot render — a
     * placeholder in one cell — rather than an exception thrown out of a scrolling grid.
     */
    private fun String.toLocalFileOrNull(): File? =
        try {
            if (startsWith("file:")) File(URI(this)) else File(this)
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        /** What this device can rasterise without asking the server (issue #7) for help. */
        val SUPPORTED_KINDS = setOf(MaterialKind.IMAGE, MaterialKind.VIDEO, MaterialKind.PDF)

        /** A grid cell is small; the extra bytes of a higher quality would never be visible. */
        const val JPEG_QUALITY = 80
    }
}
