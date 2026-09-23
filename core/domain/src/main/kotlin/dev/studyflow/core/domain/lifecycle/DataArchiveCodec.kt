package dev.studyflow.core.domain.lifecycle

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads and writes the archive's JSON document (issue #78).
 *
 * The configuration is the contract, so it is stated once here rather than at each call site:
 *
 * - `prettyPrint` because an export is the user's copy of their own data and has to be legible.
 * - `ignoreUnknownKeys` because an archive written by a *newer* build of the same schema version
 *   may carry additive fields this one has never heard of; dropping them is better than refusing
 *   the whole file.
 * - `encodeDefaults` because a reader outside this codebase cannot know what a missing field
 *   defaults to.
 */
public object DataArchiveCodec {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    public fun encode(archive: DataArchive): String = json.encodeToString(DataArchive.serializer(), archive)

    /**
     * Parses an archive document.
     *
     * A *newer* schema version is refused rather than guessed at: silently importing half of a
     * format this build does not understand is how a restore quietly loses the half it skipped.
     *
     * @throws ArchiveFormatException when the document is malformed or too new to read.
     */
    public fun decode(document: String): DataArchive {
        val archive =
            try {
                json.decodeFromString(DataArchive.serializer(), document)
            } catch (malformed: SerializationException) {
                throw ArchiveFormatException("the archive document is not valid StudyFlow JSON", malformed)
            } catch (malformed: IllegalArgumentException) {
                throw ArchiveFormatException("the archive document is not valid StudyFlow JSON", malformed)
            }

        requireSupportedVersion(archive.schemaVersion)
        return archive
    }

    private fun requireSupportedVersion(schemaVersion: Int) {
        if (schemaVersion in OLDEST_READABLE_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION) return
        val explanation =
            if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                "is newer than this app understands ($CURRENT_SCHEMA_VERSION); update StudyFlow and try again"
            } else {
                "is not a StudyFlow archive"
            }
        throw ArchiveFormatException("archive schema version $schemaVersion $explanation")
    }

    private const val OLDEST_READABLE_SCHEMA_VERSION = 1
}
