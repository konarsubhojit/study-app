package dev.studyflow.feature.settings.data

import android.content.Context
import androidx.core.net.toUri
import dev.studyflow.core.domain.lifecycle.ArchiveSink
import dev.studyflow.core.domain.lifecycle.ArchiveSinkFactory
import dev.studyflow.core.domain.lifecycle.ArchiveSource
import dev.studyflow.core.domain.lifecycle.ArchiveSourceFactory
import dev.studyflow.core.domain.materials.ArchiveEntryMetadata
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Writes an export into the document the user picked with `ACTION_CREATE_DOCUMENT` (issue #78).
 *
 * The user names the file and chooses where it goes — their Downloads folder, a USB stick, their
 * cloud drive — and the app never learns where that is. That is the point of the Storage Access
 * Framework here: an archive of everything somebody ever studied should not be written anywhere
 * the app chose on their behalf.
 */
public class SafZipArchiveSinkFactory(
    private val context: Context,
) : ArchiveSinkFactory {
    override suspend fun open(destinationUri: String): ArchiveSink {
        val stream =
            context.contentResolver.openOutputStream(destinationUri.toUri(), "wt")
                ?: throw FileNotFoundException("ContentResolver returned no stream for '$destinationUri'")
        return ZipArchiveSink(ZipOutputStream(stream.buffered()))
    }
}

internal class ZipArchiveSink(
    private val zip: ZipOutputStream,
) : ArchiveSink {
    override suspend fun writeEntry(
        path: String,
        content: InputStream,
    ) {
        zip.putNextEntry(ZipEntry(path))
        content.copyTo(zip)
        zip.closeEntry()
    }

    override suspend fun writeText(
        path: String,
        text: String,
    ) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.encodeToByteArray())
        zip.closeEntry()
    }

    override fun close(): Unit = zip.close()
}

/**
 * Reads an archive the user picked with `ACTION_OPEN_DOCUMENT`.
 *
 * A `content://` stream is not seekable, so an entry cannot be jumped to; each read walks the
 * archive from the start until it finds the entry it was asked for. That is slower than a random
 * access reader on a `File`, and it is what lets a restore work from any provider — including the
 * cloud drives an archive is most likely to be kept on.
 *
 * The entry names are read but never trusted: only the caller's
 * [dev.studyflow.core.domain.materials.ArchiveSafety] verdict decides what is opened.
 */
public class SafZipArchiveSourceFactory(
    private val context: Context,
) : ArchiveSourceFactory {
    override suspend fun open(sourceUri: String): ArchiveSource = ZipArchiveSource(context, sourceUri)
}

internal class ZipArchiveSource(
    private val context: Context,
    private val sourceUri: String,
) : ArchiveSource {
    override suspend fun entries(): List<ArchiveEntryMetadata> =
        openZip().use { zip ->
            buildList {
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) add(entry.asMetadata())
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

    override suspend fun open(entryName: String): InputStream {
        val zip = openZip()
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == entryName) return zip
            zip.closeEntry()
            entry = zip.nextEntry
        }
        zip.close()
        throw FileNotFoundException("'$entryName' is not in the archive")
    }

    override fun close(): Unit = Unit

    private fun openZip(): ZipInputStream {
        val stream =
            context.contentResolver.openInputStream(sourceUri.toUri())
                ?: throw FileNotFoundException("ContentResolver returned no stream for '$sourceUri'")
        return ZipInputStream(stream.buffered())
    }

    /**
     * A size of `-1` means the zip's local header did not declare one, which is normal for a
     * streamed archive. Reporting it as [UNKNOWN_SIZE_FALLBACK] keeps the safety check meaningful:
     * an undeclared size must not read as "zero bytes, therefore harmless".
     */
    private fun ZipEntry.asMetadata(): ArchiveEntryMetadata =
        ArchiveEntryMetadata(
            name = name,
            declaredSize = if (size >= 0) size else UNKNOWN_SIZE_FALLBACK,
            compressedSize = if (compressedSize >= 0) compressedSize else UNKNOWN_SIZE_FALLBACK,
        )

    private companion object {
        /** Small, non-zero and equal on both sides, so an undeclared size trips no ratio check. */
        const val UNKNOWN_SIZE_FALLBACK = 1L
    }
}
