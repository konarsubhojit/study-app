package dev.studyflow.core.scheduling

import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.coroutines.StandardDispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

public class UrlConnectionDownloadTransport(
    private val dispatcherProvider: DispatcherProvider = StandardDispatcherProvider,
) : DownloadTransport {
    override suspend fun download(
        request: DownloadRequest,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult =
        withContext(dispatcherProvider.io) {
            val connection = openConnection(request)
            try {
                val code = connection.responseCode
                if (request.rangeStart > 0 && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("server did not honour range request: HTTP $code")
                }
                val acceptableCodes = setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)
                if (request.rangeStart == 0L && code !in acceptableCodes) {
                    throw IOException("download failed: HTTP $code")
                }

                val totalBytes = connection.contentLengthLong.takeIf { it >= 0 }?.plus(request.rangeStart)
                val destination = File(request.localPath)
                destination.parentFile?.mkdirs()
                RandomAccessFile(destination, "rw").use { file ->
                    if (request.rangeStart == 0L) file.setLength(0)
                    file.seek(request.rangeStart)
                    var downloaded = request.rangeStart
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            file.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, totalBytes ?: request.expectedTotalBytes)
                        }
                    }
                    DownloadResult(downloadedBytes = downloaded, totalBytes = totalBytes ?: downloaded)
                }
            } finally {
                connection.disconnect()
            }
        }

    private fun openConnection(request: DownloadRequest): HttpURLConnection {
        val url = URL(request.url.url)
        val connection = url.openConnection() as? HttpURLConnection
            ?: throw IOException("download URL is not HTTP")
        if (request.rangeStart > 0) {
            connection.setRequestProperty("Range", "bytes=${request.rangeStart}-")
        }
        return connection
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
