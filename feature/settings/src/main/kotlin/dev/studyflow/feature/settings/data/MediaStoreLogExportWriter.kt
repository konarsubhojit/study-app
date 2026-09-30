package dev.studyflow.feature.settings.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.domain.result.StorageException
import dev.studyflow.feature.settings.LogExportFileWriter
import dev.studyflow.feature.settings.LogExportOutcome
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes an exported log to the user's Downloads folder through MediaStore (scoped storage).
 *
 * No permission is requested and none is needed: on Android 10 and later an app may insert its own
 * file into `MediaStore.Downloads` without `WRITE_EXTERNAL_STORAGE`, and asking for broad storage
 * access to satisfy an action the user just tapped would be exactly the kind of over-permissioning
 * scoped storage exists to end.
 *
 * Below Android 10 there is no such API, so the file is written to this app's own external files
 * directory — also permission-free — and offered through the existing `FileProvider` instead. The
 * user still gets the file; it simply arrives by sharing rather than by appearing in Downloads.
 */
public class MediaStoreLogExportWriter(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) : LogExportFileWriter {
    override suspend fun write(
        fileName: String,
        content: String,
    ): LogExportOutcome.Exported =
        withContext(dispatchers.io) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToDownloads(fileName, content)
            } else {
                writeToAppStorage(fileName, content)
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToDownloads(
        fileName: String,
        content: String,
    ): LogExportOutcome.Exported {
        val resolver = context.contentResolver
        val pending =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri =
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
                ?: throw StorageException("Downloads rejected the new file")

        // A half-written entry is worse than no entry, so anything that goes wrong takes the
        // placeholder down with it rather than leaving a truncated log in the user's Downloads.
        runCatching { publish(uri, content) }
            .onFailure { resolver.delete(uri, null, null) }
            .getOrThrow()

        return LogExportOutcome.Exported(fileName = fileName, shareUri = uri.toString(), inDownloads = true)
    }

    /**
     * Writes the bytes and only then clears `IS_PENDING`: a reader that opened the entry earlier
     * would otherwise see a truncated log and report the wrong thing.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publish(
        uri: Uri,
        content: String,
    ) {
        val resolver = context.contentResolver
        resolver.openOutputStream(uri)?.use { stream ->
            stream.write(content.toByteArray())
        } ?: throw StorageException("Downloads returned no stream")
        val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, published, null, null)
    }

    private fun writeToAppStorage(
        fileName: String,
        content: String,
    ): LogExportOutcome.Exported {
        val directory = File(context.filesDir, LOG_DIRECTORY).apply { mkdirs() }
        val file = File(directory, fileName)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return LogExportOutcome.Exported(fileName = fileName, shareUri = uri.toString(), inDownloads = false)
    }

    private companion object {
        const val MIME_TYPE = "text/plain"
        const val LOG_DIRECTORY = "logs"
    }
}
