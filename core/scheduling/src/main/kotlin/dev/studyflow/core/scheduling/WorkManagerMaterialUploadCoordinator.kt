package dev.studyflow.core.scheduling

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.common.logging.DiagnosticKey
import dev.studyflow.core.common.logging.diagnosticEvent
import dev.studyflow.core.datastore.UserSettingsStore
import dev.studyflow.core.datastore.proto.SyncMode
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.domain.materials.MaterialUploadCoordinator
import dev.studyflow.core.domain.materials.UploadWaitReason
import dev.studyflow.core.domain.sync.SyncScheduler
import dev.studyflow.core.domain.sync.SyncTrigger
import dev.studyflow.core.model.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The unique `WorkManager` name for a material's upload — one material, one work request, ever. */
public fun materialUploadWorkName(materialId: String): String = "upload:$materialId"

/** The tag every material upload request carries, so the queue can be observed as a whole. */
public const val MATERIAL_UPLOAD_WORK_TAG: String = "material-upload"

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
    private val syncScheduler: SyncScheduler,
    private val workManager: WorkManager = WorkManager.getInstance(context),
    private val constraintStatus: UploadConstraintStatus = AndroidUploadConstraintStatus(context),
    private val logger: AppLogger? = null,
) : MaterialUploadCoordinator {
    /**
     * Why the queue is not moving, or `null` when nothing is holding it back.
     *
     * Only work that is still `ENQUEUED` counts: once a transfer is `RUNNING` the constraint was
     * met, and a material that is uploading must not be described as waiting for anything.
     */
    override val waitReason: Flow<UploadWaitReason?> =
        combine(
            workManager
                .getWorkInfosByTagFlow(MATERIAL_UPLOAD_WORK_TAG)
                .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED } }
                .distinctUntilChanged(),
            settingsStore.data.map { it.syncMode }.distinctUntilChanged(),
            constraintStatus.observe(),
        ) { queued, syncMode, constraints ->
            if (queued) waitReasonFor(syncMode, constraints) else null
        }.distinctUntilChanged()

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
            syncScheduler.requestSync(SyncTrigger.OUTBOUND)
            return true
        }
        return false
    }

    private suspend fun enqueue(
        materialId: String,
        existingWorkPolicy: ExistingWorkPolicy,
    ) {
        val syncMode = settingsStore.data.first().syncMode
        val networkType = networkTypeFor(syncMode)
        val request =
            OneTimeWorkRequestBuilder<MaterialUploadWorker>()
                .setInputData(workDataOf(EXTRA_UPLOAD_MATERIAL_ID to materialId))
                .setConstraints(constraints(networkType))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .addTag(MATERIAL_UPLOAD_WORK_TAG)
                .build()
        workManager.enqueueUniqueWork(materialUploadWorkName(materialId), existingWorkPolicy, request)
        logEnqueued(materialId, syncMode, networkType)
    }

    /**
     * Records the constraint this upload now has to satisfy, at the only moment it is knowable.
     *
     * A worker held on an unmet constraint never runs and so never logs; an export containing no
     * `MaterialUpload` events at all used to be indistinguishable from a broken pipeline. This
     * line is what separates the two, and it carries only closed enum values and the material's
     * opaque id.
     */
    private fun logEnqueued(
        materialId: String,
        syncMode: SyncMode,
        networkType: NetworkType,
    ) {
        logger?.diagnostic(
            diagnosticEvent(DiagnosticCode.MaterialUploadEnqueued) {
                put(DiagnosticKey.SyncMode, syncMode)
                put(DiagnosticKey.NetworkType, networkType)
                runCatching { UUID.fromString(materialId) }
                    .getOrNull()
                    ?.takeIf { it.toString().equals(materialId, ignoreCase = true) }
                    ?.let { put(DiagnosticKey.MaterialId, it) }
            },
        )
    }

    /**
     * The constraint a queued upload has to satisfy before WorkManager will run it.
     *
     * The sync mode behind [networkType] is read fresh on every enqueue rather than cached, so a
     * setting the user just changed applies to the very next upload rather than only to ones
     * queued after an app restart.
     *
     * `SYNC_MODE_MANUAL` still constrains to a connected network rather than blocking the worker
     * outright: the material stays `Pending`/`Failed` and gets its own retry surface, but nothing
     * here yet distinguishes "wait for the user to ask" from "wait for a network" — a manual-only
     * gate is a reasonable follow-up, not required by the acceptance criteria this change targets.
     */
    private fun constraints(networkType: NetworkType): Constraints =
        Constraints
            .Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresBatteryNotLow(true)
            .build()

    public companion object {
        /** The network a material upload is allowed to use under [syncMode]. */
        public fun networkTypeFor(syncMode: SyncMode): NetworkType =
            when (syncMode) {
                SyncMode.SYNC_MODE_WIFI_ONLY -> NetworkType.UNMETERED
                SyncMode.SYNC_MODE_ANY_NETWORK, SyncMode.SYNC_MODE_MANUAL -> NetworkType.CONNECTED
            }

        /**
         * Which constraint a queued upload is still waiting for, or `null` when all of them are met.
         *
         * Battery is reported last: a device that is both offline and low will recover its network
         * first, and naming the thing the user can act on beats naming the thing they cannot.
         */
        internal fun waitReasonFor(
            syncMode: SyncMode,
            constraints: UploadConstraints,
        ): UploadWaitReason? =
            when {
                !constraints.connected -> {
                    UploadWaitReason.WAITING_FOR_NETWORK
                }

                networkTypeFor(syncMode) == NetworkType.UNMETERED && !constraints.unmetered -> {
                    UploadWaitReason.WAITING_FOR_WIFI
                }

                constraints.batteryLow -> {
                    UploadWaitReason.WAITING_FOR_BATTERY
                }

                else -> {
                    null
                }
            }
    }
}
