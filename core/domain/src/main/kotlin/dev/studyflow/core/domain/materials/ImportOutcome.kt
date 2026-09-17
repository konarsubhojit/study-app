package dev.studyflow.core.domain.materials

import dev.studyflow.core.model.Material
import kotlin.time.Duration

/** What happened when [MaterialImporter] tried to bring one URI into the catalogue. */
public sealed interface ImportOutcome {
    /** The name to show the user, even for a file that never made it into the catalogue. */
    public val displayName: String

    /** A new catalogue entry was created. */
    public data class Imported(
        val material: Material,
        override val displayName: String = material.displayName,
        /** Pages counted while streaming, for [dev.studyflow.core.model.MaterialKind.PDF] only. */
        val pageCount: Int? = null,
        /** Duration read from the container, for audio and video only. */
        val duration: Duration? = null,
    ) : ImportOutcome

    /** The file's content hash already exists in the catalogue; nothing was re-copied or re-saved. */
    public data class DuplicateFound(
        val existing: Material,
        override val displayName: String = existing.displayName,
    ) : ImportOutcome

    /** The file was read in full but refused by [ImportPolicy]. */
    public data class Rejected(
        val reason: ImportRejectionReason,
        override val displayName: String,
    ) : ImportOutcome

    /** The file could not be imported for a reason unrelated to policy. */
    public data class Failed(
        val reason: ImportFailureReason,
        override val displayName: String,
    ) : ImportOutcome
}

/** Why an import failed outright, as opposed to being refused by policy. */
public enum class ImportFailureReason {
    /** The URI could not be opened, or reading it failed partway through. */
    UNREADABLE,

    /** The import was cancelled before it finished. */
    CANCELLED,
}
