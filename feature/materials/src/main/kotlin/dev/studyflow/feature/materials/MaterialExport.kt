package dev.studyflow.feature.materials

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import dev.studyflow.core.model.Material
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Copies a cached material into the public Downloads collection.
 *
 * Returns [MaterialExportResult.Exported] only after the bytes were copied and the MediaStore row
 * was marked complete. The injectable lambdas are test seams for the MediaStore calls.
 */
internal suspend fun exportLocalMaterialToDownloads(
    context: Context,
    material: Material,
    source: MaterialPreviewSource?,
    insertDownload: (ContentValues) -> Uri? = { values ->
        context.contentResolver.insert(downloadsCollectionUri(), values)
    },
    openOutput: (Uri) -> OutputStream? = { uri -> context.contentResolver.openOutputStream(uri) },
    markFinished: (Uri) -> Unit = { uri -> context.markDownloadFinished(uri) },
    deleteDownload: (Uri) -> Unit = { uri -> context.contentResolver.delete(uri, null, null) },
): MaterialExportResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return MaterialExportResult.Unsupported
    if (source !is MaterialPreviewSource.Local) return MaterialExportResult.Failed
    val sourceFile = source.uri.toLocalFile() ?: return MaterialExportResult.Failed
    if (!sourceFile.isFile) return MaterialExportResult.Failed

    val downloadUri =
        try {
            insertDownload(material.downloadContentValues())
        } catch (exception: RuntimeException) {
            if (!exception.isExpectedProviderFailure()) throw exception
            Log.w(TAG, "Material export insert failed", exception)
            null
        } ?: return MaterialExportResult.Failed

    return try {
        if (!copyLocalFileToDownload(sourceFile, downloadUri, openOutput)) {
            deleteDownloadSafely(downloadUri, deleteDownload)
            MaterialExportResult.Failed
        } else {
            markFinished(downloadUri)
            MaterialExportResult.Exported
        }
    } catch (exception: CancellationException) {
        withContext(NonCancellable) { deleteDownloadSafely(downloadUri, deleteDownload) }
        throw exception
    } catch (exception: IOException) {
        Log.w(TAG, "Material export failed", exception)
        deleteDownloadSafely(downloadUri, deleteDownload)
        MaterialExportResult.Failed
    } catch (exception: RuntimeException) {
        if (!exception.isExpectedProviderFailure()) throw exception
        Log.w(TAG, "Material export failed", exception)
        deleteDownloadSafely(downloadUri, deleteDownload)
        MaterialExportResult.Failed
    }
}

internal enum class MaterialExportResult {
    Exported,
    Unsupported,
    Failed,
}

private suspend fun copyLocalFileToDownload(
    sourceFile: File,
    downloadUri: Uri,
    openOutput: (Uri) -> OutputStream?,
): Boolean {
    val output = openOutput(downloadUri) ?: return false
    val buffer = ByteArray(EXPORT_COPY_BUFFER_BYTES)
    output.use { target ->
        sourceFile.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                target.write(buffer, 0, read)
            }
        }
    }
    return true
}

private fun String.toLocalFile(): File? {
    val uri = toUri()
    return when (uri.scheme) {
        null -> File(this)
        "file" -> uri.path?.let(::File)
        else -> null
    }
}

private fun deleteDownloadSafely(
    uri: Uri,
    deleteDownload: (Uri) -> Unit,
) {
    try {
        deleteDownload(uri)
    } catch (exception: IOException) {
        Log.w(TAG, "Material export cleanup failed", exception)
    } catch (exception: RuntimeException) {
        if (!exception.isExpectedProviderFailure()) throw exception
        Log.w(TAG, "Material export cleanup failed", exception)
    }
}

private fun Material.downloadContentValues(): ContentValues =
    ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { EXPORT_FALLBACK_MIME_TYPE })
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

private fun Context.markDownloadFinished(uri: Uri) {
    val values =
        ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
    contentResolver.update(uri, values, null, null)
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun downloadsCollectionUri(): Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

private fun RuntimeException.isExpectedProviderFailure(): Boolean =
    this is SecurityException ||
        this is IllegalArgumentException ||
        this is IllegalStateException ||
        this is UnsupportedOperationException

private const val TAG = "MaterialExport"
private const val EXPORT_COPY_BUFFER_BYTES = 64 * 1024
private const val EXPORT_FALLBACK_MIME_TYPE = "*/*"
