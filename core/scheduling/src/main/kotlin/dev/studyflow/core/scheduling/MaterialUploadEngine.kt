package dev.studyflow.core.scheduling

import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.domain.materials.CompletedUploadPart
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.domain.materials.UploadPart
import dev.studyflow.core.domain.materials.UploadPlan
import dev.studyflow.core.domain.materials.UploadPlanner
import dev.studyflow.core.domain.materials.UploadProgressStore
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.storage.ObjectStoreException
import dev.studyflow.core.storage.UploadRequest
import dev.studyflow.core.storage.UploadedPart
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI

/** What happened to one call to [MaterialUploadEngine.upload]. */
public sealed interface UploadOutcome {
    /** [ObjectStore.completeUpload] returned, and the material is now [SyncState.Synced]. */
    public data object Synced : UploadOutcome

    /** A transient failure; the caller should schedule another attempt. */
    public data class Retryable(
        val reason: String,
    ) : UploadOutcome

    /** A failure that resending will not fix; the material is [SyncState.Failed] with `retryable = false`. */
    public data class Permanent(
        val reason: String,
    ) : UploadOutcome

    /** The material was deleted, or never existed; there is nothing left to upload. */
    public data object MaterialMissing : UploadOutcome
}

/**
 * The chunked, resumable upload itself (issue #38), kept independent of WorkManager, notifications
 * and Hilt so it can be driven from a plain unit test.
 *
 * Every part that [ObjectStore.uploadPart] acknowledges is persisted to [uploadProgressStore]
 * *before* the next part is attempted, and only [UploadPlan.remaining] parts are ever sent — so a
 * process death between two parts, or between the last part and [ObjectStore.completeUpload],
 * costs at most the one part that was in flight when it happened. The material is moved to
 * [SyncState.Synced] only once [ObjectStore.completeUpload] itself succeeds: a corrupted or
 * partial upload can therefore never read as synced, whatever else went wrong along the way.
 */
public class MaterialUploadEngine(
    private val materialRepository: MaterialRepository,
    private val uploadProgressStore: UploadProgressStore,
    private val objectStore: ObjectStore,
    private val readPart: (localPath: String, part: UploadPart) -> ByteArray = ::readPartFromDisk,
    private val onProgress: suspend (uploadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    private val logger: AppLogger? = null,
) {
    public suspend fun upload(materialId: String): UploadOutcome {
        val material =
            materialRepository.observeById(materialId).first()
        return when {
            material == null -> {
                log(materialId, MaterialTransferStage.PLAN, MaterialTransferOutcome.FAILURE, retryable = false)
                UploadOutcome.MaterialMissing
            }

            // Already synced — most likely a second run queued before the first one's success was
            // observed. Nothing to resend, and re-uploading would waste the user's data for no
            // reason, *provided* the object is really there: a build that uploaded into an
            // in-process store left rows claiming `Synced` against bytes that never existed, so
            // the claim is checked rather than trusted (issue #193).
            material.sync == SyncState.Synced -> {
                verifySyncedMaterial(material)
            }

            else -> {
                uploadMaterial(material)
            }
        }
    }

    private suspend fun uploadMaterial(material: Material): UploadOutcome {
        var stage = MaterialTransferStage.PLAN
        var partNumber: Int? = null
        var partCount: Int? = null

        // The transfer rewrites the row as parts are acknowledged, so a failure has to be recorded
        // against the newest snapshot rather than the one this function started with.
        var latest = material
        return try {
            log(material.id, stage, MaterialTransferOutcome.STARTED, retryable = true)
            stage = MaterialTransferStage.DEDUPE
            val linked = relinkIfAlreadyStored(material)
            if (linked != null) {
                linked
            } else {
                stage = MaterialTransferStage.PLAN
                transfer(material, onSnapshot = { snapshot -> latest = snapshot }) { transferStage, part, count ->
                    stage = transferStage
                    partNumber = part
                    partCount = count
                    log(material.id, stage, MaterialTransferOutcome.STARTED, retryable = true, part, count)
                }
            }
        } catch (exception: ObjectStoreException) {
            val reason = exception.message ?: exception::class.simpleName.orEmpty()
            latest.fail(reason, exception.retryable)
            log(
                material.id,
                stage,
                MaterialTransferOutcome.FAILURE,
                exception.retryable,
                partNumber,
                partCount,
                exception,
            )
            if (exception.retryable) UploadOutcome.Retryable(reason) else UploadOutcome.Permanent(reason)
        } catch (exception: IOException) {
            // The local staging copy is gone or unreadable; resending the same bytes cannot help.
            val reason = "local file unreadable: ${exception.message}"
            latest.fail(reason, retryable = false)
            log(
                material.id,
                stage,
                MaterialTransferOutcome.FAILURE,
                retryable = false,
                part = partNumber,
                partCount = partCount,
                throwable = exception,
            )
            UploadOutcome.Permanent(reason)
        }
    }

    /**
     * Checks that a material the catalogue calls `Synced` really has bytes in the store.
     *
     * A row whose object is gone — never written by a build that "uploaded" into memory, or
     * deleted since — would otherwise present a file that can never be fetched, on this device and
     * on every other device the row syncs to. The honest repair is to stop claiming it is stored:
     * a material that still has its local copy goes back to [SyncState.Pending] so this very run
     * uploads it, and one that has neither copy becomes a non-retryable failure the user can
     * re-attach or remove rather than retry forever.
     */
    private suspend fun verifySyncedMaterial(material: Material): UploadOutcome {
        val key = material.remoteKey?.let(::ObjectKey) ?: ObjectKey.ofMaterial(material.contentHash)
        val stored =
            try {
                objectStore.stat(key)
            } catch (exception: ObjectStoreException) {
                // The store could not answer; "still synced" is the safe reading of silence, so an
                // outage never costs a user their catalogue.
                log(material.id, MaterialTransferStage.REPAIR, MaterialTransferOutcome.FAILURE, exception.retryable)
                return if (exception.retryable) {
                    UploadOutcome.Retryable(exception.message ?: exception::class.simpleName.orEmpty())
                } else {
                    UploadOutcome.Synced
                }
            }
        return if (stored == null) {
            repairMissingObject(material)
        } else {
            log(material.id, MaterialTransferStage.REPAIR, MaterialTransferOutcome.SKIPPED, retryable = false)
            UploadOutcome.Synced
        }
    }

    /** Stops a material claiming to be stored, and re-uploads it when a local copy still exists. */
    private suspend fun repairMissingObject(material: Material): UploadOutcome {
        log(material.id, MaterialTransferStage.REPAIR, MaterialTransferOutcome.NOT_FOUND, retryable = false)
        uploadProgressStore.clear(material.id)
        val localPath = material.localPath
        if (localPath == null) {
            val reason = "the uploaded file is no longer in cloud storage"
            materialRepository.save(
                material.copy(sync = SyncState.Failed(reason, retryable = false), remoteKey = null),
            )
            return UploadOutcome.Permanent(reason)
        }
        val reset = material.copy(sync = SyncState.Pending, remoteKey = null)
        materialRepository.save(reset)
        return uploadMaterial(reset)
    }

    /**
     * Re-links [material] to bytes the store already holds, or `null` when they have to be sent.
     *
     * The key *is* the digest, so "has anyone already uploaded this file?" is one cheap `stat`
     * rather than a transfer: a slide deck the user imported on another device, shared into the app
     * twice, or whose upload died between `completeUpload` and the catalogue write costs no storage
     * and no bandwidth the second time round (issue #42). The size is compared as well, so a
     * truncated or partially written object is uploaded properly instead of being adopted.
     */
    private suspend fun relinkIfAlreadyStored(material: Material): UploadOutcome? {
        val stored = objectStore.stat(ObjectKey.ofMaterial(material.contentHash))
        return when {
            stored == null -> {
                log(material.id, MaterialTransferStage.DEDUPE, MaterialTransferOutcome.NOT_FOUND, retryable = false)
                null
            }

            stored.sizeBytes != material.sizeBytes || stored.contentHash != material.contentHash -> {
                log(material.id, MaterialTransferStage.DEDUPE, MaterialTransferOutcome.MISMATCH, retryable = false)
                null
            }

            else -> {
                materialRepository.save(material.copy(sync = SyncState.Synced, remoteKey = stored.key.value))
                uploadProgressStore.clear(material.id)
                log(material.id, MaterialTransferStage.DEDUPE, MaterialTransferOutcome.SUCCESS, retryable = false)
                UploadOutcome.Synced
            }
        }
    }

    /**
     * The transfer itself: everything the store does not already have, part by part.
     *
     * @param onSnapshot receives every rewritten copy of the material, so a caller that has to
     *  record a failure does so against the latest row rather than a stale one.
     */
    @Suppress("ReturnCount", "LongMethod") // Keep resumable part/complete/verify ordering visible in one pipeline.
    private suspend fun transfer(
        material: Material,
        onSnapshot: (Material) -> Unit,
        onStage: (MaterialTransferStage, Int?, Int?) -> Unit,
    ): UploadOutcome {
        var current = material
        val materialId = material.id
        val localPath = current.localPath
        if (localPath == null) {
            val reason = "material has no local file to upload"
            current.fail(reason, retryable = false)
            onStage(MaterialTransferStage.PLAN, null, null)
            log(materialId, MaterialTransferStage.PLAN, MaterialTransferOutcome.FAILURE, retryable = false)
            return UploadOutcome.Permanent(reason)
        }

        val plan = UploadPlanner.plan(current.sizeBytes, current.contentHash)
        log(materialId, MaterialTransferStage.PLAN, MaterialTransferOutcome.SUCCESS, retryable = false)

        val request = UploadRequest.ofMaterial(current.contentHash, current.sizeBytes, current.mimeType)
        onStage(MaterialTransferStage.INIT, null, null)
        val session = objectStore.initUpload(request)
        log(materialId, MaterialTransferStage.INIT, MaterialTransferOutcome.SUCCESS, retryable = false)
        val signedPartsByNumber = session.parts.associateBy { it.number }

        val completed = uploadProgressStore.completedParts(materialId).associateBy { it.number }.toMutableMap()
        current = current.markUploading(plan, completed.values).also(onSnapshot)

        if (!plan.isComplete(completed.keys)) {
            val remaining = plan.remaining(completed.keys)
            remaining.forEachIndexed { index, part ->
                onStage(MaterialTransferStage.PART, part.number, plan.parts.size)
                val signedPart =
                    signedPartsByNumber[part.number]
                        ?: error("upload session for '${current.id}' has no signed URL for part ${part.number}")
                val bytes = readPart(localPath, part)
                val uploaded = objectStore.uploadPart(session, signedPart, bytes)
                log(
                    materialId,
                    MaterialTransferStage.PART,
                    MaterialTransferOutcome.SUCCESS,
                    retryable = false,
                    part = part.number,
                    partCount = plan.parts.size,
                )
                val completedPart = CompletedUploadPart(uploaded.number, uploaded.etag, uploaded.size)
                // Every part is durably recorded the instant it is acknowledged, so a process
                // death never loses a receipt. The catalogue row's `Uploading` progress is a UI
                // nicety rather than a resume source, so it is only rewritten every few parts —
                // a many-thousand-part transfer would otherwise turn one database write per part
                // acknowledged into needless churn on the catalogue's observers.
                uploadProgressStore.recordCompletedPart(materialId, completedPart)
                completed[completedPart.number] = completedPart
                val isLastPart = index == remaining.lastIndex
                if (isLastPart || (index + 1) % PROGRESS_SAVE_INTERVAL_PARTS == 0) {
                    current = current.markUploading(plan, completed.values).also(onSnapshot)
                }
                onProgress(plan.uploadedBytes(completed.keys), plan.totalBytes)
            }
        }

        val orderedParts = plan.parts.map { part -> completed.getValue(part.number).asUploadedPart() }
        onStage(MaterialTransferStage.COMPLETE, null, null)
        val stored = objectStore.completeUpload(session, orderedParts)
        log(materialId, MaterialTransferStage.COMPLETE, MaterialTransferOutcome.SUCCESS, retryable = false)
        onStage(MaterialTransferStage.VERIFY, null, null)
        if (stored.sizeBytes != material.sizeBytes || stored.contentHash != material.contentHash) {
            val reason = "uploaded object verification failed"
            current.fail(reason, retryable = false)
            log(materialId, MaterialTransferStage.VERIFY, MaterialTransferOutcome.FAILURE, retryable = false)
            return UploadOutcome.Permanent(reason)
        }
        log(materialId, MaterialTransferStage.VERIFY, MaterialTransferOutcome.SUCCESS, retryable = false)

        // Synced first, cleared second: a crash between the two leaves stale-but-harmless part
        // rows behind a material already marked Synced, rather than a Synced object whose part
        // receipts are gone — which would force a full re-upload of a file the server already
        // has, the exact redundant work this engine exists to avoid.
        materialRepository.save(current.copy(sync = SyncState.Synced, remoteKey = stored.key.value))
        uploadProgressStore.clear(materialId)
        return UploadOutcome.Synced
    }

    private fun log(
        materialId: String,
        stage: MaterialTransferStage,
        outcome: MaterialTransferOutcome,
        retryable: Boolean,
        part: Int? = null,
        partCount: Int? = null,
        throwable: Throwable? = null,
    ) {
        logger?.materialTransfer(
            code = DiagnosticCode.MaterialUpload,
            materialId = materialId,
            details =
                MaterialTransferDetails(
                    stage = stage,
                    outcome = outcome,
                    retryable = retryable,
                    part = part,
                    partCount = partCount,
                ),
            throwable = throwable,
        )
    }

    private suspend fun Material.markUploading(
        plan: UploadPlan,
        completedParts: Collection<CompletedUploadPart>,
    ): Material {
        val uploadedBytes = plan.uploadedBytes(completedParts.map { it.number }.toSet())
        val updated = copy(sync = SyncState.Uploading(uploadedBytes, plan.totalBytes))
        materialRepository.save(updated)
        return updated
    }

    private suspend fun Material.fail(
        reason: String,
        retryable: Boolean,
    ): Material {
        val updated = copy(sync = SyncState.Failed(reason, retryable = retryable))
        materialRepository.save(updated)
        return updated
    }

    private fun CompletedUploadPart.asUploadedPart(): UploadedPart =
        UploadedPart(number = number, etag = etag, size = sizeBytes)

    public companion object {
        /** How many acknowledged parts pass between catalogue progress writes; see the loop above. */
        private const val PROGRESS_SAVE_INTERVAL_PARTS = 5

        /** Reads exactly [UploadPart.size] bytes at [UploadPart.offset] from the file at [localPath]. */
        public fun readPartFromDisk(
            localPath: String,
            part: UploadPart,
        ): ByteArray {
            val bytes = ByteArray(part.size.toInt())
            RandomAccessFile(localPath.toLocalFile(), "r").use { file ->
                file.seek(part.offset)
                file.readFully(bytes)
            }
            return bytes
        }

        private fun String.toLocalFile(): File = if (startsWith("file:")) File(URI(this)) else File(this)
    }
}
