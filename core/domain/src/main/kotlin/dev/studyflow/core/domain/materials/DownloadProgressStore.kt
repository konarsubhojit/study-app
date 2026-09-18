package dev.studyflow.core.domain.materials

/** Durable byte offset for a material download that can resume after process death (issue #39). */
public data class DownloadProgress(
    val materialId: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
) {
    init {
        require(materialId.isNotBlank()) { "DownloadProgress.materialId must not be blank" }
        require(downloadedBytes >= 0) { "downloadedBytes must not be negative, was $downloadedBytes" }
        require(totalBytes == null || totalBytes >= downloadedBytes) {
            "totalBytes must be null or at least downloadedBytes"
        }
    }
}

public interface DownloadProgressStore {
    public suspend fun progress(materialId: String): DownloadProgress?

    public suspend fun save(progress: DownloadProgress)

    public suspend fun clear(materialId: String)
}
