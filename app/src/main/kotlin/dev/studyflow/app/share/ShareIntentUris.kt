package dev.studyflow.app.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * Extracts the URIs a share intent actually carries (issue #37).
 *
 * A sender is free to put its attachments in [Intent.EXTRA_STREAM] as a single [Uri], as an
 * `ArrayList<Uri>`, or in [Intent.getClipData] instead — Android does not settle this, and different
 * senders (Drive, Gmail, a browser's "share image", a chat app) pick differently. `ClipData` is
 * preferred when present because it is the richer, more modern representation and is what the
 * platform itself populates when the user shares straight from a gallery; `EXTRA_STREAM` is the
 * fallback for senders that still only set the older extra.
 */
internal object ShareIntentUris {
    /** The shared URIs, in order, or empty when [intent] is not a recognised share intent. */
    fun extract(intent: Intent?): List<Uri> {
        if (intent == null || intent.action !in SHARE_ACTIONS) return emptyList()

        intent.clipData?.let { clipData ->
            val uris = (0 until clipData.itemCount).mapNotNull { clipData.getItemAt(it).uri }
            if (uris.isNotEmpty()) return uris
        }

        return when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::listOf).orEmpty()
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            }

            else -> {
                emptyList()
            }
        }
    }

    private val SHARE_ACTIONS = setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)
}
