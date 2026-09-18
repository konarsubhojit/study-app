package dev.studyflow.core.domain.materials

/** Schedules and controls a material download into the app-private offline cache (issue #39). */
public interface MaterialDownloadCoordinator {
    public suspend fun enqueueDownload(materialId: String)

    public fun cancelDownload(materialId: String)
}
