package dev.studyflow.core.domain.materials

import java.io.InputStream
import kotlin.time.Duration

/**
 * The metadata a content provider is willing to state about a URI, before any bytes are read.
 *
 * Every field is nullable because a provider is free to answer none of these questions — a share
 * intent from an unfamiliar app is the common case, not the exception.
 *
 * @property sizeBytes the provider's claimed size, or `null`/negative when unknown. Never trusted
 *   as a hard limit on its own: [MaterialImporter] enforces the real ceiling against bytes actually
 *   read, because a provider is free to be wrong or to lie.
 */
public data class ImportContentMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
    val mimeType: String?,
)

/**
 * Reads a picked, opened or shared URI without knowing whether it came from a photo picker, a
 * document picker or a share sheet (issue #37).
 *
 * URIs are opaque strings here rather than `android.net.Uri`: the whole import pipeline —
 * [MaterialImporter] included — stays on the JVM and testable without Robolectric, and the one
 * Android-specific adapter is the only place that ever touches a `ContentResolver`.
 */
public interface ImportContentReader {
    /** The name, size and MIME type the content provider is willing to report for [uri]. */
    public fun queryMetadata(uri: String): ImportContentMetadata

    /**
     * Opens [uri] for reading.
     *
     * @throws java.io.IOException when the URI cannot be opened — revoked, deleted at the source,
     *   or never valid in the first place.
     */
    public fun openInputStream(uri: String): InputStream

    /**
     * Best-effort request to keep read access to [uri] across process death.
     *
     * Many sources — the Photo Picker, most share-sheet senders — never grant this, and that is not
     * an error: the stream already open for this import succeeds regardless, only a *future* import
     * of the same URI would need to ask the user again. Implementations must swallow the platform's
     * refusal rather than let it fail the current import.
     */
    public fun takePersistableReadPermission(uri: String)
}

/**
 * Extracts a cheap duration for audio and video, without decoding the file (issue #37).
 *
 * Reading a container's duration field is fast; this interface exists purely so the one
 * implementation that needs `android.media.MediaMetadataRetriever` stays out of the otherwise
 * Android-free import pipeline.
 */
public fun interface DurationExtractor {
    /** The media duration reported by the container, or `null` when it could not be read. */
    public fun durationOf(
        filePath: String,
        mimeType: String,
    ): Duration?

    public companion object {
        /** Never reports a duration — the default for kinds that do not need one. */
        public val NONE: DurationExtractor = DurationExtractor { _, _ -> null }
    }
}
