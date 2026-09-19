package dev.studyflow.core.domain.materials

import dev.studyflow.core.common.coroutines.DispatcherProvider
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Streams an archive's entry listing and extracts one entry at a time (issue #40).
 *
 * [ArchiveSafety] decides what is safe; this class is the only thing in the app that turns that
 * decision into actual reads and writes, and it never trusts the archive more than
 * [ArchiveSafety] already does.
 *
 * ### Listing costs a header, not a byte
 *
 * [ZipInputStream.getNextEntry] parses the local file header without inflating the entry's
 * content, so [list] can walk a hostile archive's entire directory before a single byte of any
 * entry is read. [ArchiveSafety.inspectArchive] then turns that listing into an [ArchiveVerdict]:
 * accepted entries are safe to offer for extraction, rejected ones carry an [ArchiveRejection] the
 * UI can explain instead of a silently missing row. A stream that is truncated, corrupt, or not a
 * zip at all is not this class's problem to throw about — [list] turns any [IOException] into
 * [ArchiveListing.Malformed] instead.
 *
 * ### Sizes in the header can lie
 *
 * A local file header's declared size is exactly that: declared. An entry written with a data
 * descriptor reports `-1` until it is fully read, and even a header that does carry real-looking
 * numbers is free to disagree with what actually comes out of the decompressor — [ArchiveSafety]
 * already refuses negative sizes, but [extract] does not trust the positive ones either. The
 * per-entry cap is enforced against bytes actually copied out of the stream while copying, not
 * against [java.util.zip.ZipEntry.getSize]; a copy that crosses the cap aborts immediately and its
 * partial output is deleted rather than left behind, exactly the discipline [MaterialImporter]
 * applies to a source's declared size.
 *
 * ### One entry, re-found, re-checked
 *
 * [ZipInputStream] has no random access, so [extract] re-opens the archive from the start and scans
 * for the one entry whose raw name matches [ArchiveEntryVerdict.Accepted.name]. Because
 * [ArchiveSafety.inspectArchive] already discarded any entry that collides on normalised path with
 * an earlier one, that name is unique among accepted entries and a linear scan is unambiguous. The
 * caller's verdict is trusted for the destination path only after this class re-derives and
 * re-validates it from the raw entry the stream is actually looking at, and only after that
 * destination is confirmed to resolve underneath [destinationRoot] — defence in depth for an entry
 * that was already accepted once.
 */
public class ArchiveReader(
    private val dispatcherProvider: DispatcherProvider,
    private val limits: ArchiveLimits = ArchiveLimits.DEFAULT,
) {
    /**
     * Lists every entry the archive [opener] opens onto, and runs [ArchiveSafety.inspectArchive]
     * over the full listing before returning.
     *
     * @param opener opens a fresh stream over the archive's bytes; called exactly once.
     */
    public suspend fun list(opener: () -> InputStream): ArchiveListing =
        withContext(dispatcherProvider.io) {
            val entries = mutableListOf<ArchiveEntryMetadata>()
            try {
                ZipInputStream(opener()).use { zip ->
                    while (true) {
                        ensureActive()
                        val entry = zip.nextEntry ?: break
                        entries +=
                            ArchiveEntryMetadata(
                                name = entry.name,
                                declaredSize = entry.size,
                                compressedSize = entry.compressedSize,
                            )
                        zip.closeEntry()
                    }
                }
            } catch (_: IOException) {
                return@withContext ArchiveListing.Malformed
            }
            ArchiveListing.Listed(ArchiveSafety.inspectArchive(entries, limits))
        }

    /**
     * Extracts [entry] — one accepted by a prior [list] call — into [destinationRoot].
     *
     * @param opener opens a fresh stream over the same archive bytes [list] saw; called exactly
     *   once.
     * @param isCancelled polled between reads so a large extraction can be stopped cooperatively;
     *   defaults to never cancelling for callers that only rely on structured coroutine
     *   cancellation (this suspend function is itself a cancellation point via [ensureActive]).
     */
    public suspend fun extract(
        opener: () -> InputStream,
        entry: ArchiveEntryVerdict.Accepted,
        destinationRoot: File,
        isCancelled: () -> Boolean = { false },
    ): ArchiveExtractionResult =
        withContext(dispatcherProvider.io) {
            val destination =
                resolveDestination(destinationRoot, entry)
                    ?: return@withContext ArchiveExtractionResult.Rejected(ArchiveRejection.PATH_TRAVERSAL)

            destination.parentFile?.mkdirs()
            val result =
                try {
                    findAndCopyEntry(opener, entry, destination, isCancelled)
                } catch (_: IOException) {
                    ArchiveExtractionResult.Failed
                }
            if (result !is ArchiveExtractionResult.Extracted) destination.delete()
            result
        }

    private fun findAndCopyEntry(
        opener: () -> InputStream,
        entry: ArchiveEntryVerdict.Accepted,
        destination: File,
        isCancelled: () -> Boolean,
    ): ArchiveExtractionResult {
        ZipInputStream(opener()).use { zip ->
            while (true) {
                val zipEntry = zip.nextEntry ?: return ArchiveExtractionResult.Failed
                if (zipEntry.name != entry.name) {
                    zip.closeEntry()
                    continue
                }
                // The stream is trusted for content, never for the path: re-derive it from the raw
                // entry actually found rather than reusing the path the original verdict computed.
                if (ArchiveSafety.safeRelativePath(zipEntry.name) != entry.path) {
                    return ArchiveExtractionResult.Rejected(ArchiveRejection.PATH_TRAVERSAL)
                }
                return copyEntry(zip, destination, isCancelled)
            }
        }
    }

    /** Confirms [entry]'s normalised path resolves underneath [root], returning that file or `null`. */
    private fun resolveDestination(
        root: File,
        entry: ArchiveEntryVerdict.Accepted,
    ): File? {
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, entry.path).canonicalFile
        val withinRoot =
            candidate == canonicalRoot || candidate.path.startsWith(canonicalRoot.path + File.separatorChar)
        return candidate.takeIf { withinRoot }
    }

    /** Copies [input]'s current entry to [destination], capping on bytes actually read. */
    private fun copyEntry(
        input: InputStream,
        destination: File,
        isCancelled: () -> Boolean,
    ): ArchiveExtractionResult {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        return FileOutputStream(destination).use { output ->
            copyLoop(input, output, destination, buffer, totalBytes = 0L, isCancelled)
        }
    }

    /**
     * One copy step per call, recursing instead of looping so every exit — cancelled, capped, or
     * done — is a plain guard-clause return rather than a loop full of `break`s.
     */
    private tailrec fun copyLoop(
        input: InputStream,
        output: FileOutputStream,
        destination: File,
        buffer: ByteArray,
        totalBytes: Long,
        isCancelled: () -> Boolean,
    ): ArchiveExtractionResult {
        if (isCancelled()) return ArchiveExtractionResult.Cancelled

        val read = input.read(buffer)
        // The running total — not the entry's declared size — is what actually bounds the output,
        // because the declared size is exactly what a hostile archive can lie about.
        return when {
            read == -1 -> {
                ArchiveExtractionResult.Extracted(destination, totalBytes)
            }

            totalBytes + read > limits.maxEntryBytes -> {
                ArchiveExtractionResult.Rejected(ArchiveRejection.ENTRY_TOO_LARGE)
            }

            else -> {
                output.write(buffer, 0, read)
                copyLoop(input, output, destination, buffer, totalBytes + read, isCancelled)
            }
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

/** Outcome of [ArchiveReader.list]. */
public sealed interface ArchiveListing {
    /** The archive was read in full; [verdict] says what is safe to extract. */
    public data class Listed(
        val verdict: ArchiveVerdict,
    ) : ArchiveListing

    /** The stream was not a readable zip: truncated, corrupt, or not an archive at all. */
    public data object Malformed : ArchiveListing
}

/** Outcome of [ArchiveReader.extract]. */
public sealed interface ArchiveExtractionResult {
    /** [file] holds exactly [bytesWritten] bytes copied from the entry. */
    public data class Extracted(
        val file: File,
        val bytesWritten: Long,
    ) : ArchiveExtractionResult

    /** Stopped by the caller's cancellation check; any partial output has already been deleted. */
    public data object Cancelled : ArchiveExtractionResult

    /** Refused for [reason]; any partial output has already been deleted. */
    public data class Rejected(
        val reason: ArchiveRejection,
    ) : ArchiveExtractionResult

    /** The stream failed, or the entry could not be re-found; any partial output has been deleted. */
    public data object Failed : ArchiveExtractionResult
}
