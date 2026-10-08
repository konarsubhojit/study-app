package dev.studyflow.feature.materials

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.Material
import java.io.File

@Composable
internal fun PreviewActions(
    material: Material,
    source: MaterialPreviewSource?,
    onShare: (Material, MaterialPreviewSource?) -> Unit,
    onExport: (Material, MaterialPreviewSource?) -> Unit,
    onDelete: () -> Unit,
    onStartStudySession: (String?) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextButton(onClick = { onShare(material, source) }, enabled = source is MaterialPreviewSource.Local) {
            Text(text = "Share")
        }
        TextButton(onClick = { onExport(material, source) }, enabled = source is MaterialPreviewSource.Local) {
            Text(text = "Export")
        }
        TextButton(onClick = onDelete) {
            Text(text = "Delete")
        }
        TextButton(onClick = { onStartStudySession(material.subjectId) }) {
            Text(text = "Study")
        }
    }
}

internal fun shareLocalMaterial(
    context: Context,
    material: Material,
    source: MaterialPreviewSource?,
) {
    if (source !is MaterialPreviewSource.Local) return
    val sourceFile = source.uri.toLocalFileOrNull() ?: return
    val intent =
        Intent(Intent.ACTION_SEND)
            .setType(material.mimeType)
            .putExtra(
                Intent.EXTRA_STREAM,
                FileProvider.getUriForFile(context, "${context.packageName}.files", sourceFile),
            ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, material.displayName))
}

/** Guesses a MIME type from a filename extension; falls back to a wildcard when unknown or absent. */
internal fun guessMimeType(fileName: String): String {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: ALL_MIME_TYPES
}

/** Opens [file] with an installed viewer via [FileProvider], never a raw `file://` Uri. */
internal fun Context.openLocalFile(
    file: File,
    mimeType: String,
): Boolean {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType.ifBlank { ALL_MIME_TYPES })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return start(intent)
}

/** Starts [intent], tolerating a device with nothing installed to handle it. */
private fun Context.start(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

private const val ALL_MIME_TYPES = "*/*"
