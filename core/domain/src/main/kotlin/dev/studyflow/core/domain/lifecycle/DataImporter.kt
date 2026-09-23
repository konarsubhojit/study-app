package dev.studyflow.core.domain.lifecycle

import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.domain.materials.ArchiveEntryVerdict
import dev.studyflow.core.domain.materials.ArchiveLimits
import dev.studyflow.core.domain.materials.ArchiveSafety
import dev.studyflow.core.domain.result.DomainResult
import dev.studyflow.core.model.Material
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/**
 * What a restore actually changed.
 *
 * Separating "added" from "already had" is the observable form of the idempotence promise: a user
 * who imports the same archive twice sees a second run that added nothing, instead of having to
 * trust that it did not.
 *
 * @property rejectedEntries entries the archive safety rules refused — a traversal path, an
 *   implausible compression ratio. Non-zero means the file was tampered with or corrupted.
 */
public data class ImportSummary(
    val added: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val restoredFiles: Int = 0,
    val rejectedEntries: Int = 0,
)

/**
 * Restores an archive onto this device (issue #78).
 *
 * Two things make this safe to point at a file that arrived from anywhere:
 *
 * - **Nothing is extracted before the whole archive has been inspected.** [ArchiveSafety] rejects
 *   traversal paths, duplicate destinations and bomb-shaped entries, and the copy loop re-checks
 *   the byte count as it streams, because the sizes the archive declares are attacker-controlled.
 * - **Nothing is written outside the app's own stores.** Entry names never become file paths here;
 *   restored bytes are handed to [MaterialFileStore] under the material's own id, so even an entry
 *   called `../../databases/studyflow.db` could only ever land as one more cached material.
 */
public class DataImporter(
    private val sourceFactory: ArchiveSourceFactory,
    private val localData: LocalDataReader,
    private val writer: LocalDataWriter,
    private val materialFiles: MaterialFileStore,
    private val dispatcherProvider: DispatcherProvider,
    private val limits: ArchiveLimits = ArchiveLimits.DEFAULT,
) {
    /**
     * Merges the archive at [sourceUri] into the local database.
     *
     * @param sourceUri an opaque document URI, as returned by `ACTION_OPEN_DOCUMENT`.
     */
    @Suppress("TooGenericExceptionCaught")
    public suspend fun import(sourceUri: String): DomainResult<ImportSummary> =
        withContext(dispatcherProvider.io) {
            try {
                sourceFactory.open(sourceUri).use { source ->
                    DomainResult.Success(source.restore())
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                DomainResult.Failure(archiveFailure(failure))
            }
        }

    private suspend fun ArchiveSource.restore(): ImportSummary {
        val verdict = ArchiveSafety.inspectArchive(entries(), limits)
        val safeEntries =
            verdict.accepted.associateBy(
                keySelector = ArchiveEntryVerdict.Accepted::path,
                valueTransform = { it },
            )
        val document =
            safeEntries[ARCHIVE_DOCUMENT_ENTRY]
                ?: throw ArchiveFormatException(
                    "the file does not contain $ARCHIVE_DOCUMENT_ENTRY and is not a StudyFlow archive",
                )

        val archive = DataArchiveCodec.decode(readText(document))
        val snapshot = localData.readSnapshot()

        val subjects = ArchiveMergePolicy.mergeSubjects(archive.subjects, snapshot.subjects)
        val sessions = ArchiveMergePolicy.mergeSessions(archive.sessions, snapshot.sessions)
        val tasks = ArchiveMergePolicy.mergeTasks(archive.tasks, snapshot.tasks)
        val materials =
            restoreMaterials(
                actions = ArchiveMergePolicy.planMaterials(archive.materials, snapshot.materials),
                safeEntries = safeEntries,
            )

        val batch =
            ImportBatch(
                subjects = subjects.writes,
                sessions = sessions.writes,
                tasks = tasks.writes,
                materials = materials.outcome.writes,
            )
        if (!batch.isEmpty) writer.applyImport(batch)

        return ImportSummary(
            added =
                subjects.inserted.size + sessions.inserted.size + tasks.inserted.size +
                    materials.outcome.inserted.size,
            updated = sessions.updated.size + tasks.updated.size + materials.outcome.updated.size,
            unchanged = subjects.skipped + sessions.skipped + tasks.skipped + materials.outcome.skipped,
            restoredFiles = materials.restoredFiles,
            rejectedEntries = verdict.rejected.size,
        )
    }

    /** Restores the bytes of every material the merge decided to write, then builds the rows. */
    private suspend fun ArchiveSource.restoreMaterials(
        actions: List<MaterialMergeAction>,
        safeEntries: Map<String, ArchiveEntryVerdict.Accepted>,
    ): RestoredMaterials {
        val inserted = mutableListOf<Material>()
        val updated = mutableListOf<Material>()
        var skipped = 0
        var restoredFiles = 0

        actions.forEach { action ->
            currentCoroutineContext().ensureActive()
            if (action.decision == MergeDecision.SKIP) {
                skipped++
                return@forEach
            }

            val localPath =
                action.localPath ?: action.record.archiveEntry
                    ?.let(safeEntries::get)
                    ?.let { entry -> restoreFile(action.record.id, entry)?.also { restoredFiles++ } }
            val material = action.record.toModel(localPath)
            if (action.decision == MergeDecision.INSERT) inserted += material else updated += material
        }

        return RestoredMaterials(
            outcome = MergeOutcome(inserted = inserted, updated = updated, skipped = skipped),
            restoredFiles = restoredFiles,
        )
    }

    /**
     * Copies one material's bytes out of the archive.
     *
     * A file the archive promised but does not actually contain is not a reason to fail the whole
     * restore: the catalogue row is still worth having, and the bytes can be fetched from the
     * server later.
     */
    private suspend fun ArchiveSource.restoreFile(
        materialId: String,
        entry: ArchiveEntryVerdict.Accepted,
    ): String? =
        try {
            open(entry.name).use { content ->
                materialFiles.store(materialId, LimitedInputStream(content, limits.maxEntryBytes))
            }
        } catch (_: IOException) {
            null
        }

    /** Reads a small text entry, refusing one that turns out to be larger than it claimed. */
    private suspend fun ArchiveSource.readText(entry: ArchiveEntryVerdict.Accepted): String =
        open(entry.name).use { stream ->
            LimitedInputStream(stream, MAX_DOCUMENT_BYTES).readBytes().decodeToString()
        }

    private data class RestoredMaterials(
        val outcome: MergeOutcome<Material>,
        val restoredFiles: Int,
    )

    private companion object {
        /** Generous for a catalogue of tens of thousands of records, bounded against a bomb. */
        const val MAX_DOCUMENT_BYTES = 64L * 1024 * 1024
    }
}

/**
 * Stops reading once [limit] bytes have been produced.
 *
 * The entry sizes in a zip directory are written by whoever built the zip, so a reader that trusts
 * them has no defence at all against an entry that decompresses to far more than it declared. This
 * makes the limit a property of the read rather than of the metadata.
 */
internal class LimitedInputStream(
    private val delegate: InputStream,
    private val limit: Long,
) : InputStream() {
    private var produced = 0L

    override fun read(): Int {
        val value = delegate.read()
        if (value != -1) count(1)
        return value
    }

    override fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val read = delegate.read(destination, offset, length)
        if (read > 0) count(read.toLong())
        return read
    }

    override fun available(): Int = delegate.available()

    override fun close(): Unit = delegate.close()

    private fun count(bytes: Long) {
        produced += bytes
        if (produced > limit) throw IOException("archive entry is larger than the declared $limit bytes")
    }
}
