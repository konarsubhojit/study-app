package dev.studyflow.core.scheduling

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
) {
    public suspend fun upload(materialId: String): UploadOutcome {
        val material = materialRepository.observeById(materialId).first() ?: return UploadOutcome.MaterialMissing

        // Already synced — most likely a second run queued before the first one's success was
        // observed. Nothing to resend, and re-uploading would waste the user's data for no reason.
        if (material.sync == SyncState.Synced) return UploadOutcome.Synced

        return try {
            relinkIfAlreadyStored(material) ?: transfer(material)
        } catch (exception: ObjectStoreException) {
            val reason = exception.message ?: exception::class.simpleName.orEmpty()
            material.fail(reason, exception.retryable)
            if (exception.retryable) UploadOutcome.Retryable(reason) else UploadOutcome.Permanent(reason)
        } catch (exception: IOException) {
            // The local staging copy is gone or unreadable; resending the same bytes cannot help.
            val reason = "local file unreadable: ${exception.message}"
            material.fail(reason, retryable = false)
            UploadOutcome.Permanent(reason)
        }
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
        val stored = objectStore.stat(ObjectKey.ofMaterial(material.contentHash)) ?: return null
        if (stored.sizeBytes != material.sizeBytes) return null

        materialRepository.save(material.copy(sync = SyncState.Synced, remoteKey = stored.key.value))
        uploadProgressStore.clear(material.id)
        return UploadOutcome.Synced
    }

    /** The transfer itself: everything the store does not already have, part by part. */
    @Suppress("ReturnCount")
    private suspend fun transfer(material: Material): UploadOutcome {
        var current = material
        val materialId = material.id
        val localPath = current.localPath
        if (localPath == null) {
            val reason = "material has no local file to upload"
            current.fail(reason, retryable = false)
            return UploadOutcome.Permanent(reason)
        }

        val plan = UploadPlanner.plan(current.sizeBytes, current.contentHash)

        val request = UploadRequest.ofMaterial(current.contentHash, current.sizeBytes, current.mimeType)
        val session = objectStore.initUpload(request)
        val signedPartsByNumber = session.parts.associateBy { it.number }

        val completed = uploadProgressStore.completedParts(materialId).associateBy { it.number }.toMutableMap()
        current = current.markUploading(plan, completed.values)

        if (!plan.isComplete(completed.keys)) {
            val remaining = plan.remaining(completed.keys)
            remaining.forEachIndexed { index, part ->
                val signedPart =
                    signedPartsByNumber[part.number]
                        ?: error("upload session for '${current.id}' has no signed URL for part ${part.number}")
                val bytes = readPart(localPath, part)
                val uploaded = objectStore.uploadPart(session, signedPart, bytes)
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
                    current = current.markUploading(plan, completed.values)
                }
                onProgress(plan.uploadedBytes(completed.keys), plan.totalBytes)
            }
        }

        val orderedParts = plan.parts.map { part -> completed.getValue(part.number).asUploadedPart() }
        val stored = objectStore.completeUpload(session, orderedParts)

        // Synced first, cleared second: a crash between the two leaves stale-but-harmless part
        // rows behind a material already marked Synced, rather than a Synced object whose part
        // receipts are gone — which would force a full re-upload of a file the server already
        // has, the exact redundant work this engine exists to avoid.
        materialRepository.save(current.copy(sync = SyncState.Synced, remoteKey = stored.key.value))
        uploadProgressStore.clear(materialId)
        return UploadOutcome.Synced
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
