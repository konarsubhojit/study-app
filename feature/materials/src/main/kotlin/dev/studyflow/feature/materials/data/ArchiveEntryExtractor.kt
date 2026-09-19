package dev.studyflow.feature.materials.data

import dev.studyflow.core.domain.materials.ArchiveEntryVerdict
import dev.studyflow.core.domain.materials.ArchiveExtractionResult
import dev.studyflow.core.domain.materials.ArchiveListing
import dev.studyflow.core.domain.materials.ArchiveReader
import java.io.File
import java.io.FileInputStream

/**
 * Opens a material's cached archive file and delegates to [ArchiveReader] (issue #40).
 *
 * Every extracted entry lands under its own per-material subfolder of [archivesDirectory], never
 * directly inside the materials directory an original import occupies by id — the two would
 * otherwise collide, since an import's destination file and an extraction's destination directory
 * could share the same name. The subfolder still lives under the `materials/` root the app's
 * `FileProvider` already exposes, so a caller can hand an extracted file's path straight to the
 * same `FileProvider.getUriForFile` call every other local preview uses.
 */
public class ArchiveEntryExtractor(
    private val reader: ArchiveReader,
    private val archivesDirectory: () -> File,
) {
    /** Lists [archiveFile]'s entries; see [ArchiveReader.list]. */
    public suspend fun list(archiveFile: File): ArchiveListing = reader.list { FileInputStream(archiveFile) }

    /**
     * Extracts [entry] from [archiveFile] into a subfolder scoped to [materialId].
     *
     * @param isCancelled polled during the copy so a large extraction can be stopped.
     */
    public suspend fun extract(
        archiveFile: File,
        materialId: String,
        entry: ArchiveEntryVerdict.Accepted,
        isCancelled: () -> Boolean = { false },
    ): ArchiveExtractionResult =
        reader.extract(
            opener = { FileInputStream(archiveFile) },
            entry = entry,
            destinationRoot = File(archivesDirectory(), materialId),
            isCancelled = isCancelled,
        )
}
