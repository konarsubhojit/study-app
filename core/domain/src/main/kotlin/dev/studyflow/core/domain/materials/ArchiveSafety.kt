package dev.studyflow.core.domain.materials

/**
 * Decides whether an archive entry is safe to extract.
 *
 * ### Zip slip
 *
 * A zip entry's name is attacker-controlled text, not a validated path. An entry called
 * `../../../../data/data/dev.studyflow/databases/studyflow.db` extracted naively will happily
 * overwrite the app's own database. The defence is to normalise the name and refuse anything that
 * escapes the extraction root, before a single byte is written.
 *
 * ### Zip bombs
 *
 * A 42 KB archive can expand to petabytes. Since the app lets users open arbitrary archives they
 * downloaded from the internet, "trust the declared size" is not a defence; the limits below cap
 * total output, per-entry output and compression ratio, and the reader must also enforce them while
 * streaming, because the declared sizes are themselves attacker-controlled.
 *
 * The app treats archives as read-only previews, which removes most of the risk. These checks cover
 * the rest.
 */
public object ArchiveSafety {
    private const val PATH_SEPARATOR = '/'
    private val FORBIDDEN_CHARACTERS = charArrayOf('\u0000')

    /**
     * Normalises an archive entry name to a path relative to the extraction root.
     *
     * @return the safe relative path, or `null` when the entry escapes the root or is malformed.
     */
    public fun safeRelativePath(entryName: String): String? {
        if (entryName.isBlank()) return null
        if (entryName.any { it in FORBIDDEN_CHARACTERS }) return null

        // Backslashes are path separators on the machine that probably produced the archive, so an
        // entry called `..\..\secrets` must be treated as traversal rather than as a valid filename.
        val unified = entryName.replace('\\', PATH_SEPARATOR)

        // A leading slash or a Windows drive letter makes the path absolute, which ignores the root.
        if (unified.startsWith(PATH_SEPARATOR) || hasDriveLetter(unified)) return null

        return normaliseWithinRoot(unified)
    }

    /**
     * Collapses `.` and `..` segments, refusing anything that would climb above the root.
     *
     * @return the normalised path, or `null` when the entry escapes or normalises to nothing.
     */
    private fun normaliseWithinRoot(path: String): String? {
        val resolved = ArrayDeque<String>()
        for (segment in path.split(PATH_SEPARATOR)) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (resolved.isEmpty()) return null else resolved.removeLast()
                else -> resolved.addLast(segment)
            }
        }
        return resolved.takeIf { it.isNotEmpty() }?.joinToString(PATH_SEPARATOR.toString())
    }

    /**
     * Checks a single entry against path and size rules.
     *
     * @param declaredSize uncompressed size as claimed by the archive metadata.
     * @param compressedSize stored size of the entry.
     */
    public fun inspect(
        entryName: String,
        declaredSize: Long,
        compressedSize: Long,
        limits: ArchiveLimits = ArchiveLimits.DEFAULT,
    ): ArchiveEntryVerdict {
        val path =
            safeRelativePath(entryName)
                ?: return ArchiveEntryVerdict.Rejected(entryName, ArchiveRejection.PATH_TRAVERSAL)

        if (declaredSize < 0 || compressedSize < 0) {
            return ArchiveEntryVerdict.Rejected(entryName, ArchiveRejection.MALFORMED_METADATA)
        }
        if (declaredSize > limits.maxEntryBytes) {
            return ArchiveEntryVerdict.Rejected(entryName, ArchiveRejection.ENTRY_TOO_LARGE)
        }
        if (exceedsRatio(declaredSize, compressedSize, limits)) {
            return ArchiveEntryVerdict.Rejected(entryName, ArchiveRejection.SUSPICIOUS_COMPRESSION_RATIO)
        }
        return ArchiveEntryVerdict.Accepted(entryName, path, declaredSize)
    }

    /**
     * Checks a whole archive, applying per-entry rules plus the aggregate limits that catch bombs
     * assembled from many individually innocuous entries.
     */
    public fun inspectArchive(
        entries: List<ArchiveEntryMetadata>,
        limits: ArchiveLimits = ArchiveLimits.DEFAULT,
    ): ArchiveVerdict {
        if (entries.size > limits.maxEntries) {
            return ArchiveVerdict(
                accepted = emptyList(),
                rejected =
                    entries.map {
                        ArchiveEntryVerdict.Rejected(it.name, ArchiveRejection.TOO_MANY_ENTRIES)
                    },
            )
        }

        val accepted = mutableListOf<ArchiveEntryVerdict.Accepted>()
        val rejected = mutableListOf<ArchiveEntryVerdict.Rejected>()
        val seenPaths = mutableSetOf<String>()
        var total = 0L

        for (entry in entries) {
            when (val verdict = inspect(entry.name, entry.declaredSize, entry.compressedSize, limits)) {
                is ArchiveEntryVerdict.Rejected -> {
                    rejected += verdict
                }

                is ArchiveEntryVerdict.Accepted -> {
                    total += verdict.declaredSize
                    when {
                        total > limits.maxTotalBytes -> {
                            rejected += ArchiveEntryVerdict.Rejected(entry.name, ArchiveRejection.ARCHIVE_TOO_LARGE)
                        }

                        // Two entries normalising to the same path means the second silently
                        // overwrites the first — a quieter variant of the same attack.
                        !seenPaths.add(verdict.path) -> {
                            rejected += ArchiveEntryVerdict.Rejected(entry.name, ArchiveRejection.DUPLICATE_PATH)
                        }

                        else -> {
                            accepted += verdict
                        }
                    }
                }
            }
        }
        return ArchiveVerdict(accepted = accepted, rejected = rejected)
    }

    private fun exceedsRatio(
        declaredSize: Long,
        compressedSize: Long,
        limits: ArchiveLimits,
    ): Boolean {
        // Tiny entries compress absurdly well for boring reasons; only judge the ratio once an
        // entry is big enough for it to mean something.
        if (declaredSize < limits.ratioCheckThresholdBytes) return false
        if (compressedSize == 0L) return true
        return declaredSize.toDouble() / compressedSize > limits.maxCompressionRatio
    }

    private fun hasDriveLetter(path: String): Boolean = path.length >= 2 && path[1] == ':' && path[0].isLetter()
}

/** The metadata an archive reader can see before extracting anything. */
public data class ArchiveEntryMetadata(
    val name: String,
    val declaredSize: Long,
    val compressedSize: Long,
)

/** Caps that turn an untrusted archive into a bounded amount of work. */
public data class ArchiveLimits(
    val maxEntries: Int,
    val maxEntryBytes: Long,
    val maxTotalBytes: Long,
    val maxCompressionRatio: Double,
    val ratioCheckThresholdBytes: Long,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxEntryBytes > 0) { "maxEntryBytes must be positive" }
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
        require(maxCompressionRatio > 1) { "maxCompressionRatio must be greater than 1" }
    }

    public companion object {
        private const val MIB = 1024L * 1024L

        /** Generous enough for a folder of past papers, far too small for a bomb. */
        public val DEFAULT: ArchiveLimits =
            ArchiveLimits(
                maxEntries = 10_000,
                maxEntryBytes = 512 * MIB,
                maxTotalBytes = 2048 * MIB,
                maxCompressionRatio = 200.0,
                ratioCheckThresholdBytes = MIB,
            )
    }
}

/** Outcome for one entry. */
public sealed interface ArchiveEntryVerdict {
    public val name: String

    /** Safe to extract, at the normalised [path] relative to the extraction root. */
    public data class Accepted(
        override val name: String,
        val path: String,
        val declaredSize: Long,
    ) : ArchiveEntryVerdict

    /** Must not be extracted. [reason] is specific so the UI can explain rather than just fail. */
    public data class Rejected(
        override val name: String,
        val reason: ArchiveRejection,
    ) : ArchiveEntryVerdict
}

/** Why an archive entry was refused. */
public enum class ArchiveRejection {
    /** The name escapes the extraction root, is absolute, or contains illegal characters. */
    PATH_TRAVERSAL,

    /** Two entries resolve to the same destination path. */
    DUPLICATE_PATH,

    /** A single entry claims more space than the per-entry cap. */
    ENTRY_TOO_LARGE,

    /** The archive as a whole claims more space than the total cap. */
    ARCHIVE_TOO_LARGE,

    /** The archive declares more entries than the cap. */
    TOO_MANY_ENTRIES,

    /** Expansion ratio consistent with a decompression bomb. */
    SUSPICIOUS_COMPRESSION_RATIO,

    /** Negative or otherwise impossible sizes. */
    MALFORMED_METADATA,
}

/** Outcome for a whole archive. */
public data class ArchiveVerdict(
    val accepted: List<ArchiveEntryVerdict.Accepted>,
    val rejected: List<ArchiveEntryVerdict.Rejected>,
) {
    /** True when nothing was refused, so the archive can be previewed without a warning. */
    public val isClean: Boolean get() = rejected.isEmpty()

    /** Total declared size of everything that would be extracted. */
    public val totalDeclaredBytes: Long get() = accepted.sumOf { it.declaredSize }
}
