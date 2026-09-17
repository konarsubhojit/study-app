package dev.studyflow.core.domain.materials

import dev.studyflow.core.model.MaterialKind

/**
 * Decides whether an incoming file may join the catalogue (issue #37).
 *
 * The limits are keyed by [MaterialKind] rather than one flat ceiling: a lecture recording is
 * routinely hundreds of megabytes and a scanned page is routinely a few, and a single number would
 * either reject ordinary videos or wave through implausible documents.
 *
 * The blocklist exists because `ACTION_OPEN_DOCUMENT` accepts every MIME type by design — this is a
 * study-materials catalogue, not a general file manager, and an installable package or an
 * executable script has no legitimate reason to be in it.
 */
public object ImportPolicy {
    /**
     * Evaluates a file against [limits].
     *
     * @param sizeBytes the size to check — the declared size before a copy starts, or the actual
     *   number of bytes copied once it has, so the same call site serves both the cheap early
     *   rejection and the authoritative one.
     */
    public fun evaluate(
        kind: MaterialKind,
        mimeType: String,
        fileName: String,
        sizeBytes: Long,
        limits: ImportLimits = ImportLimits.DEFAULT,
    ): ImportVerdict {
        val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when {
            normalizedMime in limits.blockedMimeTypes || extension in limits.blockedExtensions -> {
                ImportVerdict.Rejected(ImportRejectionReason.BLOCKED_TYPE)
            }

            sizeBytes > limits.maxBytesFor(kind) -> {
                ImportVerdict.Rejected(ImportRejectionReason.FILE_TOO_LARGE)
            }

            else -> {
                ImportVerdict.Allowed
            }
        }
    }
}

/** Per-kind size ceilings and the small set of MIME types and extensions refused outright. */
public data class ImportLimits(
    val maxBytesByKind: Map<MaterialKind, Long>,
    val defaultMaxBytes: Long,
    val blockedMimeTypes: Set<String>,
    val blockedExtensions: Set<String>,
) {
    init {
        require(defaultMaxBytes > 0) { "defaultMaxBytes must be positive" }
        require(maxBytesByKind.values.all { it > 0 }) { "every configured limit must be positive" }
    }

    /** The ceiling that applies to [kind], falling back to [defaultMaxBytes] when unconfigured. */
    public fun maxBytesFor(kind: MaterialKind): Long = maxBytesByKind[kind] ?: defaultMaxBytes

    public companion object {
        private const val MIB = 1024L * 1024L

        /**
         * Generous enough for a semester of lecture recordings, small enough that a phone's
         * internal storage cannot be exhausted by one import.
         */
        public val DEFAULT: ImportLimits =
            ImportLimits(
                maxBytesByKind =
                    mapOf(
                        MaterialKind.VIDEO to 4096 * MIB,
                        MaterialKind.AUDIO to 1024 * MIB,
                        MaterialKind.IMAGE to 256 * MIB,
                        MaterialKind.PDF to 512 * MIB,
                        MaterialKind.PRESENTATION to 512 * MIB,
                        MaterialKind.DOCUMENT to 256 * MIB,
                        MaterialKind.SPREADSHEET to 256 * MIB,
                        MaterialKind.ARCHIVE to 2048 * MIB,
                        MaterialKind.TEXT to 64 * MIB,
                    ),
                defaultMaxBytes = 512 * MIB,
                // Executables and installable packages: this is a study-materials catalogue, and a
                // malicious `.apk` renamed with an innocuous extension is exactly what content
                // sniffing (rather than the extension alone) catches here.
                blockedMimeTypes =
                    setOf(
                        "application/vnd.android.package-archive",
                        "application/x-msdownload",
                        "application/x-msdos-program",
                        "application/vnd.microsoft.portable-executable",
                        "application/x-sh",
                        "application/x-shellscript",
                    ),
                blockedExtensions = setOf("apk", "exe", "msi", "bat", "cmd", "sh", "dex", "so", "dll"),
            )
    }
}

/** The outcome of checking a file against [ImportLimits]. */
public sealed interface ImportVerdict {
    public data object Allowed : ImportVerdict

    public data class Rejected(
        val reason: ImportRejectionReason,
    ) : ImportVerdict
}

/** Why a file was refused, so the UI can say something more useful than "import failed". */
public enum class ImportRejectionReason {
    /** The file (or its declared size) exceeds the ceiling for its kind. */
    FILE_TOO_LARGE,

    /** The MIME type or extension is on the blocklist regardless of size. */
    BLOCKED_TYPE,
}
