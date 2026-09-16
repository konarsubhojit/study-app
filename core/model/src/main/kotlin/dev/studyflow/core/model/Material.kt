package dev.studyflow.core.model

import kotlin.time.Instant

/**
 * A file the user uploaded: lecture slides, a scanned page, a recording, an archive of past papers.
 *
 * The catalogue is offline-first — a row exists the moment the user picks a file, long before any
 * byte leaves the device — so [sync] is part of the model rather than a hidden implementation
 * detail.
 *
 * @property contentHash SHA-256 of the file's bytes. Doubles as the storage key, which makes
 *   uploads idempotent and lets two identical files share one blob instead of paying twice for
 *   storage and egress.
 * @property localUri where the staged or cached copy lives, if there is one. `null` means the file
 *   exists only in the cloud and must be fetched before it can be opened.
 * @property pinnedForOffline when true, the cache eviction policy must not reclaim this file.
 */
public data class Material(
    val id: String,
    val folderId: String? = null,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentHash: ContentHash,
    val createdAt: Instant,
    val sync: SyncState = SyncState.Pending,
    val localUri: String? = null,
    val pinnedForOffline: Boolean = false,
    val encrypted: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Material.id must not be blank" }
        require(displayName.isNotBlank()) { "Material.displayName must not be blank" }
        require(sizeBytes >= 0) { "Material.sizeBytes must not be negative, was $sizeBytes" }
    }

    /** Coarse category used to choose a previewer and an icon. */
    public val kind: MaterialKind get() = MaterialKind.of(mimeType, displayName)
}

/** A user-created folder in the materials catalogue. */
public data class Folder(
    val id: String,
    val parentId: String? = null,
    val name: String,
    val createdAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "Folder.id must not be blank" }
        require(name.isNotBlank()) { "Folder.name must not be blank" }
        require(id != parentId) { "a folder cannot be its own parent" }
    }
}

/**
 * A SHA-256 digest, lower-case hex.
 *
 * Content addressing is what makes a chunked upload safely resumable: the server can be asked
 * "do you already have this?" before a single byte is sent, and the finished object can be verified
 * against the digest the device computed before it started.
 */
@JvmInline
public value class ContentHash(
    public val hex: String,
) {
    init {
        require(hex.length == HEX_LENGTH) { "a SHA-256 digest is $HEX_LENGTH hex characters, got ${hex.length}" }
        require(hex.all { it in '0'..'9' || it in 'a'..'f' }) { "digest must be lower-case hexadecimal" }
    }

    override fun toString(): String = hex

    public companion object {
        private const val HEX_LENGTH = 64
    }
}

/** Where a [Material] is in its journey from "just picked" to "safely in the cloud". */
public sealed interface SyncState {
    /** Staged locally, queued for upload, no bytes sent yet. */
    public data object Pending : SyncState

    /** Upload in flight. [uploadedBytes] drives a determinate progress indicator. */
    public data class Uploading(
        val uploadedBytes: Long,
        val totalBytes: Long,
    ) : SyncState {
        init {
            require(uploadedBytes in 0..totalBytes) {
                "uploadedBytes must be within 0..$totalBytes, was $uploadedBytes"
            }
        }

        /** Progress in `0f..1f`; a zero-byte file counts as complete. */
        public val fraction: Float
            get() = if (totalBytes == 0L) 1f else uploadedBytes.toFloat() / totalBytes
    }

    /** Uploaded and verified against its [ContentHash]. */
    public data object Synced : SyncState

    /** Upload failed. [retryable] distinguishes a flaky network from a rejected file. */
    public data class Failed(
        val reason: String,
        val retryable: Boolean,
    ) : SyncState
}

/**
 * Coarse file categories, derived from MIME type with a filename-extension fallback.
 *
 * Content resolvers lie: plenty of providers hand back `application/octet-stream` for a perfectly
 * ordinary `.docx`, so the extension is consulted as a second opinion.
 */
public enum class MaterialKind {
    IMAGE,
    VIDEO,
    AUDIO,
    PDF,
    DOCUMENT,
    SPREADSHEET,
    PRESENTATION,
    ARCHIVE,
    TEXT,
    OTHER,
    ;

    public companion object {
        private val byExtension: Map<String, MaterialKind> =
            buildMap {
                listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif").forEach { put(it, IMAGE) }
                listOf("mp4", "mkv", "webm", "mov", "avi", "3gp").forEach { put(it, VIDEO) }
                listOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac").forEach { put(it, AUDIO) }
                put("pdf", PDF)
                listOf("doc", "docx", "odt", "rtf", "pages").forEach { put(it, DOCUMENT) }
                listOf("xls", "xlsx", "ods", "csv", "tsv", "numbers").forEach { put(it, SPREADSHEET) }
                listOf("ppt", "pptx", "odp", "key").forEach { put(it, PRESENTATION) }
                listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz").forEach { put(it, ARCHIVE) }
                listOf("txt", "md", "json", "xml", "yaml", "yml", "log").forEach { put(it, TEXT) }
            }

        private val byMimeSubtype: Map<String, MaterialKind> =
            mapOf(
                "pdf" to PDF,
                "zip" to ARCHIVE,
                "x-7z-compressed" to ARCHIVE,
                "x-rar-compressed" to ARCHIVE,
                "x-tar" to ARCHIVE,
                "gzip" to ARCHIVE,
                "msword" to DOCUMENT,
                "vnd.openxmlformats-officedocument.wordprocessingml.document" to DOCUMENT,
                "vnd.oasis.opendocument.text" to DOCUMENT,
                "rtf" to DOCUMENT,
                "vnd.ms-excel" to SPREADSHEET,
                "vnd.openxmlformats-officedocument.spreadsheetml.sheet" to SPREADSHEET,
                "vnd.oasis.opendocument.spreadsheet" to SPREADSHEET,
                "vnd.ms-powerpoint" to PRESENTATION,
                "vnd.openxmlformats-officedocument.presentationml.presentation" to PRESENTATION,
                "vnd.oasis.opendocument.presentation" to PRESENTATION,
                "json" to TEXT,
                "xml" to TEXT,
            )

        /**
         * Classifies a file.
         *
         * @param mimeType MIME type reported by the content provider; may be blank or generic.
         * @param fileName used to recover the type when [mimeType] is unhelpful.
         */
        public fun of(
            mimeType: String,
            fileName: String = "",
        ): MaterialKind {
            fromMime(mimeType.trim().lowercase())?.let { return it }
            return byExtension[fileName.substringAfterLast('.', "").lowercase()] ?: OTHER
        }

        private fun fromMime(mime: String): MaterialKind? {
            if (mime.isEmpty()) return null
            val type = mime.substringBefore('/')
            val subtype = mime.substringAfter('/', "").substringBefore(';')
            return when (type) {
                "image" -> IMAGE
                "video" -> VIDEO
                "audio" -> AUDIO
                "text" -> byMimeSubtype[subtype] ?: TEXT
                else -> byMimeSubtype[subtype]
            }
        }
    }
}
