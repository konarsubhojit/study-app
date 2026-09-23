package dev.studyflow.core.domain.lifecycle

import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.domain.result.DomainError
import dev.studyflow.core.domain.result.DomainResult
import dev.studyflow.core.domain.result.toDomainError
import dev.studyflow.core.model.Material
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * What an export put in the archive.
 *
 * @property materialsWithoutBytes materials whose cached file was missing, so only their metadata
 *   was exported. Surfaced rather than swallowed: the user should know their archive is not a
 *   complete backup of the files, and the cause ("that one was never downloaded on this device")
 *   is actionable.
 */
public data class ExportSummary(
    val subjects: Int,
    val sessions: Int,
    val tasks: Int,
    val materials: Int,
    val materialFiles: Int,
    val materialsWithoutBytes: Int,
) {
    public val records: Int get() = subjects + sessions + tasks + materials
}

/**
 * Writes the user's whole dataset to a destination they chose (issue #78).
 *
 * The archive is a zip with a fixed layout:
 *
 * ```
 * studyflow-archive.json     every session (with its event log), task, subject and material record
 * materials/<id>-<name>      the original cached bytes of each material that has a local copy
 * ```
 *
 * One JSON document plus opaque file entries, rather than a file per record, because a restore has
 * to be a single atomic decision and because a student's archive is something they may open in a
 * text editor to check what is actually in it.
 */
public class DataExporter(
    private val localData: LocalDataReader,
    private val materialFiles: MaterialFileStore,
    private val sinkFactory: ArchiveSinkFactory,
    private val clock: Clock,
    private val dispatcherProvider: DispatcherProvider,
) {
    /**
     * Writes an archive of everything to [destinationUri].
     *
     * @param destinationUri an opaque, already-created document URI, as returned by
     *   `ACTION_CREATE_DOCUMENT`. The domain never parses it.
     */
    @Suppress("TooGenericExceptionCaught")
    public suspend fun export(destinationUri: String): DomainResult<ExportSummary> =
        withContext(dispatcherProvider.io) {
            try {
                val snapshot = localData.readSnapshot()
                sinkFactory.open(destinationUri).use { sink ->
                    DomainResult.Success(sink.writeArchive(snapshot))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                DomainResult.Failure(archiveFailure(failure))
            }
        }

    private suspend fun ArchiveSink.writeArchive(snapshot: LocalDataSnapshot): ExportSummary {
        val exportedFiles = mutableMapOf<String, String>()
        var missingBytes = 0

        snapshot.materials.forEach { material ->
            currentCoroutineContext().ensureActive()
            val entry = material.archiveEntryName()
            when (val content = material.localPath?.let { materialFiles.open(it) }) {
                null -> {
                    missingBytes++
                }

                else -> {
                    content.use { writeEntry(entry, it) }
                    exportedFiles[material.id] = entry
                }
            }
        }

        val archive =
            DataArchive(
                exportedAt = clock.now().toString(),
                deviceId = snapshot.deviceId,
                subjects = snapshot.subjects.map { it.toArchived() },
                sessions = snapshot.sessions.map { it.toArchived() },
                tasks = snapshot.tasks.map { it.toArchived() },
                materials = snapshot.materials.map { it.toArchived(exportedFiles[it.id]) },
            )
        writeText(ARCHIVE_DOCUMENT_ENTRY, DataArchiveCodec.encode(archive))

        return ExportSummary(
            subjects = archive.subjects.size,
            sessions = archive.sessions.size,
            tasks = archive.tasks.size,
            materials = archive.materials.size,
            materialFiles = exportedFiles.size,
            materialsWithoutBytes = missingBytes,
        )
    }

    /**
     * The entry a material's bytes are written to.
     *
     * The id prefix guarantees uniqueness — two subjects can legitimately hold two files called
     * `notes.pdf` — and the sanitised display name is what makes the archive browsable outside the
     * app. Every character that could mean something to a path parser is replaced, so an archive
     * this app *writes* can never be the malicious archive its own reader defends against.
     */
    private fun Material.archiveEntryName(): String =
        "$ARCHIVE_MATERIALS_DIRECTORY/$id-${displayName.sanitisedForArchive()}"

    private fun String.sanitisedForArchive(): String =
        map { character -> if (character.isLetterOrDigit() || character in ALLOWED_NAME_CHARACTERS) character else '_' }
            .joinToString("")
            .take(MAX_NAME_LENGTH)
            .ifBlank { "file" }

    private companion object {
        val ALLOWED_NAME_CHARACTERS = setOf('.', '-', '_', ' ')
        const val MAX_NAME_LENGTH = 100
    }
}

/** Failure vocabulary shared by export and import; see [DomainError] for how it reaches the UI. */
internal fun archiveFailure(cause: Throwable): DomainError =
    when (cause) {
        is ArchiveFormatException -> DomainError.Validation
        else -> cause.toDomainError()
    }
