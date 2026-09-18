package dev.studyflow.core.scheduling

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.studyflow.core.domain.materials.DownloadProgress
import dev.studyflow.core.domain.materials.DownloadProgressStore

public class SharedPreferencesDownloadProgressStore(
    private val preferences: SharedPreferences,
) : DownloadProgressStore {
    override suspend fun progress(materialId: String): DownloadProgress? {
        val downloadedBytes = preferences.getLong(bytesKey(materialId), MISSING)
        if (downloadedBytes == MISSING) return null
        val totalBytes = preferences.getLong(totalKey(materialId), MISSING).takeUnless { it == MISSING }
        return DownloadProgress(materialId, downloadedBytes, totalBytes)
    }

    override suspend fun save(progress: DownloadProgress) {
        preferences.edit {
            putLong(bytesKey(progress.materialId), progress.downloadedBytes)
            val totalBytes = progress.totalBytes
            if (totalBytes != null) {
                putLong(totalKey(progress.materialId), totalBytes)
            } else {
                remove(totalKey(progress.materialId))
            }
        }
    }

    override suspend fun clear(materialId: String) {
        preferences.edit {
            remove(bytesKey(materialId))
            remove(totalKey(materialId))
        }
    }

    private fun bytesKey(materialId: String): String = "download.$materialId.bytes"

    private fun totalKey(materialId: String): String = "download.$materialId.total"

    private companion object {
        const val MISSING = -1L
    }
}
