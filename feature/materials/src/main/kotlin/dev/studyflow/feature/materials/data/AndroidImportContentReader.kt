package dev.studyflow.feature.materials.data

import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import androidx.core.net.toUri
import dev.studyflow.core.domain.materials.ImportContentMetadata
import dev.studyflow.core.domain.materials.ImportContentReader
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * The one place [dev.studyflow.core.domain.materials.MaterialImporter] touches a `ContentResolver`
 * (issue #37).
 *
 * URIs cross that boundary as plain strings so the importer itself stays off the Android SDK and
 * testable on the JVM; this class exists purely to translate the string back into a `Uri` and ask
 * the platform the same three questions every time, whether the URI came from the Photo Picker,
 * `ACTION_OPEN_DOCUMENT`, or a share intent.
 */
public class AndroidImportContentReader(
    private val context: Context,
) : ImportContentReader {
    override fun queryMetadata(uri: String): ImportContentMetadata {
        val parsed = uri.toUri()
        val resolver = context.contentResolver
        var displayName: String? = null
        var sizeBytes: Long? = null

        resolver
            .query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
                }
            }

        return ImportContentMetadata(
            displayName = displayName,
            sizeBytes = sizeBytes,
            mimeType = resolver.getType(parsed),
        )
    }

    override fun openInputStream(uri: String): InputStream {
        val parsed = uri.toUri()
        return context.contentResolver.openInputStream(parsed)
            ?: throw FileNotFoundException("ContentResolver returned no stream for '$uri'")
    }

    override fun takePersistableReadPermission(uri: String) {
        // The Photo Picker and most share-sheet senders never grant this, which is expected rather
        // than an error: the stream already opened for the current import does not need it, only a
        // *future* re-import of the same URI would. `ImportContentReader`'s contract requires this
        // refusal to be swallowed here rather than surfaced as an import failure.
        try {
            context.contentResolver.takePersistableUriPermission(
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Expected for non-persistable sources; see the note above.
        }
    }
}
