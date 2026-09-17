package dev.studyflow.feature.materials.data

import android.media.MediaMetadataRetriever
import dev.studyflow.core.domain.materials.DurationExtractor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reads a container's declared duration without decoding a single frame (issue #37).
 *
 * [MediaMetadataRetriever] parses only the header — the `moov` atom of an MP4, the equivalent for
 * other containers — which is why this is "cheap" in the sense [DurationExtractor] promises: it
 * costs milliseconds regardless of whether the file is a three-minute recording or a three-hour
 * lecture. [MediaMetadataRetriever.release] is called explicitly rather than through
 * [AutoCloseable]: that interface was only added in API 29, and this app's minimum is lower.
 */
public class AndroidDurationExtractor : DurationExtractor {
    override fun durationOf(
        filePath: String,
        mimeType: String,
    ): Duration? =
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(filePath)
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0 }
                    ?.milliseconds
            } finally {
                retriever.release()
            }
        }.getOrNull()
}
