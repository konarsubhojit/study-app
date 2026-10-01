package dev.studyflow.core.scheduling

import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.domain.materials.MaterialUploadCoordinator
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.storage.ObjectStoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Asked, after a sync pass, to confirm that what the catalogue calls stored really is.
 *
 * A port rather than the class itself so [SyncWorker] stays testable without an object store.
 */
public fun interface MaterialStorageAudit {
    public suspend fun verifyStoredMaterials()
}

/**
 * Finds catalogue rows that claim to be stored and are not, and makes them true again (issue #193).
 *
 * A build whose `ObjectStore` kept bytes in memory completed every upload stage honestly and wrote
 * `Synced` rows carrying keys for objects that only ever existed in one process. Those rows have
 * since synced to the server and to other devices, where every download fails. Nothing else in the
 * app ever revisits a `Synced` material, so without this the corruption is permanent.
 *
 * The repair is general rather than a migration for one device's five rows: any client that ran
 * such a build has the same damage, and a row whose object a user deleted elsewhere is repaired by
 * exactly the same rule.
 *
 * Each material is checked at most once per process ([verified]) and each run checks at most
 * [maxChecksPerRun], so a large catalogue costs a handful of `stat` calls per sync rather than one
 * per material every time.
 */
public class MaterialStorageReconciler(
    private val materialRepository: MaterialRepository,
    private val objectStore: ObjectStore,
    private val uploadCoordinator: MaterialUploadCoordinator,
    private val logger: AppLogger? = null,
    private val maxChecksPerRun: Int = DEFAULT_CHECKS_PER_RUN,
) : MaterialStorageAudit {
    private val mutex = Mutex()
    private val verified = mutableSetOf<String>()

    /** Verifies the next few unverified `Synced` materials, repairing any whose object is gone. */
    override suspend fun verifyStoredMaterials() {
        mutex.withLock {
            val candidates =
                materialRepository
                    .observeAll()
                    .first()
                    .filter { it.sync == SyncState.Synced && !it.deleted && it.id !in verified }
                    .take(maxChecksPerRun)
            candidates.forEach { material ->
                // A store that cannot answer leaves the material unverified rather than repaired:
                // "we could not ask" must never be read as "the file is gone".
                val stored =
                    try {
                        objectStore.stat(keyOf(material))
                    } catch (_: ObjectStoreException) {
                        return@withLock
                    }
                verified += material.id
                if (stored == null) repair(material)
            }
        }
    }

    private suspend fun repair(material: Material) {
        log(material.id, MaterialTransferOutcome.NOT_FOUND)
        if (material.localPath == null) {
            val reason = "the uploaded file is no longer in cloud storage"
            materialRepository.save(
                material.copy(sync = SyncState.Failed(reason, retryable = false), remoteKey = null),
            )
            return
        }
        materialRepository.save(material.copy(sync = SyncState.Pending, remoteKey = null))
        // Verified no longer holds: the row is pending now, and the upload that follows is what
        // makes it synced again.
        verified -= material.id
        uploadCoordinator.enqueueUpload(material.id)
        log(material.id, MaterialTransferOutcome.STARTED)
    }

    private fun keyOf(material: Material): ObjectKey =
        material.remoteKey?.takeIf { it.isNotBlank() }?.let(::ObjectKey)
            ?: ObjectKey.ofMaterial(material.contentHash)

    private fun log(
        materialId: String,
        outcome: MaterialTransferOutcome,
    ) {
        logger?.materialTransfer(
            code = DiagnosticCode.MaterialUpload,
            materialId = materialId,
            details =
                MaterialTransferDetails(
                    stage = MaterialTransferStage.REPAIR,
                    outcome = outcome,
                    retryable = false,
                ),
        )
    }

    private companion object {
        /** Enough to repair a damaged catalogue within a few syncs without a burst of requests. */
        const val DEFAULT_CHECKS_PER_RUN = 20
    }
}
