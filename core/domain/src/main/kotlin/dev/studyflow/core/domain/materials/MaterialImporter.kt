package dev.studyflow.core.domain.materials

import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.MaterialKind
import dev.studyflow.core.model.SyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration

/**
 * Brings one URI — from the Photo Picker, `ACTION_OPEN_DOCUMENT`, or a share intent — into the
 * app-private catalogue (issue #37).
 *
 * A single streaming pass does everything that needs the bytes: copies them to durable app-private
 * storage, hashes them, sniffs the first bytes for a real file signature, counts PDF pages, and
 * enforces the size ceiling against bytes actually read rather than against whatever the source
 * claimed. Nothing here buffers the whole file — memory use is the copy buffer plus a small sniff
 * window, regardless of whether the file is one kilobyte or several hundred megabytes.
 *
 * A partially written destination file is never left behind: the `finally` block in [import]
 * deletes it unless the catalogue entry (or the duplicate short-circuit) was actually decided,
 * whether the cause was a policy rejection, an I/O failure, or cancellation.
 */
public class MaterialImporter(
    private val contentReader: ImportContentReader,
    private val repository: MaterialRepository,
    private val destinationDirectory: () -> File,
    private val clock: Clock,
    private val dispatcherProvider: DispatcherProvider,
    private val durationExtractor: DurationExtractor = DurationExtractor.NONE,
    private val limits: ImportLimits = ImportLimits.DEFAULT,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * Imports [uri] into the catalogue, or explains why it did not join it.
     *
     * @param folderId the folder the new entry should belong to; `null` for the top level.
     */
    public suspend fun import(
        uri: String,
        folderId: String? = null,
    ): ImportOutcome =
        withContext(dispatcherProvider.io) {
            val metadata =
                queryMetadataOrNull(uri)
                    ?: return@withContext ImportOutcome.Failed(ImportFailureReason.UNREADABLE, fallbackName(uri))

            val displayName = metadata.displayName?.takeIf(String::isNotBlank) ?: fallbackName(uri)
            val provisionalKind = MaterialKind.of(metadata.mimeType.orEmpty(), displayName)

            // A source that is honest and clearly over budget need never be opened at all.
            if (isDeclaredOverBudget(metadata.sizeBytes, provisionalKind)) {
                return@withContext ImportOutcome.Rejected(ImportRejectionReason.FILE_TOO_LARGE, displayName)
            }

            contentReader.runCatchingPermission(uri)

            val id = idGenerator()
            val destination = File(destinationDirectory().apply { mkdirs() }, id)
            var success = false
            try {
                performImport(uri, id, folderId, destination, metadata, displayName, provisionalKind)
                    .also { success = it is ImportOutcome.Imported }
            } catch (violation: ImportPolicyViolation) {
                ImportOutcome.Rejected(violation.reason, displayName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                ImportOutcome.Failed(ImportFailureReason.UNREADABLE, displayName)
            } catch (_: SecurityException) {
                ImportOutcome.Failed(ImportFailureReason.UNREADABLE, displayName)
            } finally {
                if (!success) destination.delete()
            }
        }

    /** Everything after the early, stream-free rejections: the actual streaming pass and its verdict. */
    private suspend fun performImport(
        uri: String,
        id: String,
        folderId: String?,
        destination: File,
        metadata: ImportContentMetadata,
        displayName: String,
        provisionalKind: MaterialKind,
    ): ImportOutcome {
        val copy = copyAndDigest(uri, destination, provisionalKind)

        val sniffed = FileSignatureSniffer.sniff(copy.sniffWindow, copy.sniffedBytes)
        val officeMime =
            if (sniffed == FileSignature.ZIP) {
                OoxmlContainerSniffer.sniff(copy.sniffWindow, copy.sniffedBytes)
            } else {
                null
            }
        val resolvedMime = resolveMimeType(metadata.mimeType, sniffed, officeMime, displayName)
        val kind = MaterialKind.of(resolvedMime, displayName)
        val hash = ContentHash(copy.digestBytes.toHex())

        return rejectionOutcome(kind, resolvedMime, displayName, copy.totalBytes)
            ?: repository.findByContentHash(hash)?.let { existing -> ImportOutcome.DuplicateFound(existing) }
            ?: saveAndBuildOutcome(id, folderId, destination, displayName, resolvedMime, kind, hash, copy)
    }

    private fun rejectionOutcome(
        kind: MaterialKind,
        mimeType: String,
        displayName: String,
        totalBytes: Long,
    ): ImportOutcome.Rejected? {
        val verdict = ImportPolicy.evaluate(kind, mimeType, displayName, totalBytes, limits)
        return (verdict as? ImportVerdict.Rejected)?.let { ImportOutcome.Rejected(it.reason, displayName) }
    }

    @Suppress("LongParameterList")
    private suspend fun saveAndBuildOutcome(
        id: String,
        folderId: String?,
        destination: File,
        displayName: String,
        resolvedMime: String,
        kind: MaterialKind,
        hash: ContentHash,
        copy: CopyResult,
    ): ImportOutcome.Imported {
        val pageCount = if (kind == MaterialKind.PDF) copy.pdfPageCounter.pageCount() else null
        val duration = durationOf(kind, destination, resolvedMime)
        val material =
            Material(
                id = id,
                folderId = folderId,
                displayName = displayName,
                mimeType = resolvedMime,
                sizeBytes = copy.totalBytes,
                contentHash = hash,
                createdAt = clock.now(),
                sync = SyncState.Pending,
                localPath = destination.toURI().toString(),
                pageCount = pageCount,
                duration = duration,
            )
        repository.save(material)

        return ImportOutcome.Imported(material = material, pageCount = pageCount, duration = duration)
    }

    /** Streams [uri] into [destination], updating a digest, a sniff window and a page counter. */
    private suspend fun copyAndDigest(
        uri: String,
        destination: File,
        provisionalKind: MaterialKind,
    ): CopyResult =
        withContext(dispatcherProvider.io) {
            val digest = MessageDigest.getInstance("SHA-256")
            val sniffWindow = ByteArray(OoxmlContainerSniffer.SCAN_WINDOW_BYTES)
            var sniffedBytes = 0
            var totalBytes = 0L
            val pdfPageCounter = PdfPageCounter()
            val maxAllowed = limits.maxBytesFor(provisionalKind)

            contentReader.openInputStream(uri).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break

                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        pdfPageCounter.accept(buffer, read)
                        totalBytes += read
                        sniffedBytes = growSniffWindow(sniffWindow, sniffedBytes, buffer, read)

                        // A source may under-report its size or not report one at all; the running
                        // total — not the declared one — is what actually bounds memory and disk.
                        if (totalBytes > maxAllowed) {
                            throw ImportPolicyViolation(ImportRejectionReason.FILE_TOO_LARGE)
                        }
                    }
                }
            }

            CopyResult(digest, sniffWindow, sniffedBytes, totalBytes, pdfPageCounter)
        }

    private fun growSniffWindow(
        sniffWindow: ByteArray,
        sniffedBytes: Int,
        buffer: ByteArray,
        read: Int,
    ): Int {
        if (sniffedBytes >= sniffWindow.size) return sniffedBytes
        val toCopy = minOf(read, sniffWindow.size - sniffedBytes)
        System.arraycopy(buffer, 0, sniffWindow, sniffedBytes, toCopy)
        return sniffedBytes + toCopy
    }

    private fun queryMetadataOrNull(uri: String): ImportContentMetadata? =
        try {
            contentReader.queryMetadata(uri)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun isDeclaredOverBudget(
        declaredSize: Long?,
        kind: MaterialKind,
    ): Boolean = declaredSize != null && declaredSize >= 0 && declaredSize > limits.maxBytesFor(kind)

    private fun durationOf(
        kind: MaterialKind,
        destination: File,
        mimeType: String,
    ): Duration? =
        if (kind == MaterialKind.AUDIO || kind == MaterialKind.VIDEO) {
            runCatching { durationExtractor.durationOf(destination.absolutePath, mimeType) }.getOrNull()
        } else {
            null
        }

    private fun ImportContentReader.runCatchingPermission(uri: String) {
        try {
            takePersistableReadPermission(uri)
        } catch (_: SecurityException) {
            // Best-effort by contract; the read already succeeded regardless.
        }
    }

    private fun resolveMimeType(
        declared: String?,
        sniffed: FileSignature?,
        officeMime: String?,
        fileName: String,
    ): String {
        val normalizedDeclared =
            declared
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.takeIf(String::isNotBlank)
        // A confidently detected office package entry (`word/`, `xl/`, `ppt/`) is a stronger claim
        // than the coarse "it is a ZIP" a plain signature match makes, so it wins outright rather
        // than going through the same top-level comparison a merely generic archive would.
        val signatureMime = officeMime ?: trumpingSignatureMime(normalizedDeclared, sniffed)
        return signatureMime
            ?: normalizedDeclared
            ?: EXTENSION_MIME_FALLBACK[fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)]
            ?: GENERIC_MIME
    }

    private fun trumpingSignatureMime(
        normalizedDeclared: String?,
        sniffed: FileSignature?,
    ): String? = sniffed?.takeIf { mimeTrumpedBySignature(normalizedDeclared, it) }?.mimeType

    private fun mimeTrumpedBySignature(
        normalizedDeclared: String?,
        sniffed: FileSignature,
    ): Boolean {
        val declaredIsGeneric = normalizedDeclared == null || normalizedDeclared == GENERIC_MIME
        val declaredTopLevel = normalizedDeclared?.substringBefore('/')
        val sniffedTopLevel = sniffed.mimeType.substringBefore('/')
        return declaredIsGeneric || declaredTopLevel != sniffedTopLevel
    }

    private fun fallbackName(uri: String): String {
        val lastSegment = uri.substringAfterLast('/').substringBefore('?').takeIf(String::isNotBlank)
        return lastSegment ?: "imported-file-${idGenerator().take(SHORT_ID_LENGTH)}"
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }

    private class CopyResult(
        val digest: MessageDigest,
        val sniffWindow: ByteArray,
        val sniffedBytes: Int,
        val totalBytes: Long,
        val pdfPageCounter: PdfPageCounter,
    ) {
        /** [MessageDigest.digest] finalises the digest; called exactly once, here. */
        val digestBytes: ByteArray by lazy { digest.digest() }
    }

    /** Escapes the copy loop the instant a lying or unbounded source crosses its ceiling. */
    private class ImportPolicyViolation(
        val reason: ImportRejectionReason,
    ) : RuntimeException()

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val GENERIC_MIME = "application/octet-stream"
        const val SHORT_ID_LENGTH = 8

        val EXTENSION_MIME_FALLBACK: Map<String, String> =
            mapOf(
                "pdf" to "application/pdf",
                "txt" to "text/plain",
                "jpg" to "image/jpeg",
                "jpeg" to "image/jpeg",
                "png" to "image/png",
                "mp3" to "audio/mpeg",
                "mp4" to "video/mp4",
                "zip" to "application/zip",
                "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            )
    }
}
