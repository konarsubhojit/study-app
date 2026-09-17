package dev.studyflow.core.scheduling

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import dev.studyflow.core.datastore.UserSettingsStore
import dev.studyflow.core.datastore.proto.SyncMode
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.domain.materials.MaterialUploadCoordinator
import dev.studyflow.core.model.SyncState
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** The unique `WorkManager` name for a material's upload — one material, one work request, ever. */
public fun materialUploadWorkName(materialId: String): String = "upload:$materialId"

/**
 * Turns a material into `WorkManager` work, or decides it needs none (issue #38).
 *
 * A material whose content is already stored — because a *different* material with the same
 * [dev.studyflow.core.model.ContentHash] has already finished uploading — is never queued at all:
 * the object store keys a material's bytes by that hash (`ObjectKey.ofMaterial`), so a second
 * upload of identical content would send the same bytes twice for nothing. This material is marked
 * synced directly instead, borrowing the already-uploaded object's key.
 */
public class WorkManagerMaterialUploadCoordinator(
    private val context: Context,
    private val materialRepository: MaterialRepository,
    private val settingsStore: UserSettingsStore,
    private val workManager: WorkManager = WorkManager.getInstance(context),
) : MaterialUploadCoordinator {
    override suspend fun enqueueUpload(materialId: String) {
        if (adoptExistingUploadIfAny(materialId)) return
        // `KEEP`: a second call for a material already queued or running (a duplicate share intent,
        // a screen re-collecting the same import outcome) must not restart or duplicate the work.
        enqueue(materialId, ExistingWorkPolicy.KEEP)
    }

    override fun cancelUpload(materialId: String) {
        workManager.cancelUniqueWork(materialUploadWorkName(materialId))
    }

    override suspend fun retryUpload(materialId: String) {
        if (adoptExistingUploadIfAny(materialId)) return
        // `REPLACE`: the previous attempt, successful or not, is done; a manual retry always starts
        // a fresh work request rather than being absorbed by `KEEP` into whatever is already there.
        enqueue(materialId, ExistingWorkPolicy.REPLACE)
    }

    /**
     * Marks [materialId] synced by borrowing another material's already-uploaded object under the
     * same content hash, without enqueueing any work — for [enqueueUpload] and a manual
     * [retryUpload] alike, since the same bytes may have finished uploading elsewhere between the
     * two calls.
     *
     * @return `true` when [materialId] is already synced or was just adopted, meaning the caller
     *   must not enqueue an upload.
     */
    private suspend fun adoptExistingUploadIfAny(materialId: String): Boolean {
        val material = materialRepository.observeById(materialId).first() ?: return true
        if (material.sync == SyncState.Synced) return true

        val existingSynced = materialRepository.findByContentHash(material.contentHash)
        if (existingSynced != null && existingSynced.id != materialId && existingSynced.sync == SyncState.Synced) {
            materialRepository.save(material.copy(sync = SyncState.Synced, remoteKey = existingSynced.remoteKey))
            return true
        }
        return false
    }

    private suspend fun enqueue(
        materialId: String,
        existingWorkPolicy: ExistingWorkPolicy,
    ) {
        val request =
            OneTimeWorkRequestBuilder<MaterialUploadWorker>()
                .setInputData(workDataOf(EXTRA_UPLOAD_MATERIAL_ID to materialId))
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .addTag(materialUploadWorkName(materialId))
                .build()
        workManager.enqueueUniqueWork(materialUploadWorkName(materialId), existingWorkPolicy, request)
    }

    /**
     * Wi-Fi-only is read fresh on every enqueue rather than cached, so a setting the user just
     * changed applies to the very next upload rather than only to ones queued after an app restart.
     *
     * `SYNC_MODE_MANUAL` still constrains to a connected network rather than blocking the worker
     * outright: the material stays `Pending`/`Failed` and gets its own retry surface, but nothing
     * here yet distinguishes "wait for the user to ask" from "wait for a network" — a manual-only
     * gate is a reasonable follow-up, not required by the acceptance criteria this change targets.
     */
    private suspend fun constraints(): Constraints {
        val networkType =
            when (settingsStore.data.first().syncMode) {
                SyncMode.SYNC_MODE_WIFI_ONLY -> NetworkType.UNMETERED
                SyncMode.SYNC_MODE_ANY_NETWORK, SyncMode.SYNC_MODE_MANUAL -> NetworkType.CONNECTED
            }
        return Constraints
            .Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresBatteryNotLow(true)
            .build()
    }
}
