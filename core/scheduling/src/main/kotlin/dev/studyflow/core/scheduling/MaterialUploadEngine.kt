package dev.studyflow.core.scheduling

import dev.studyflow.core.domain.materials.CompletedUploadPart
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.domain.materials.UploadPart
import dev.studyflow.core.domain.materials.UploadPlan
import dev.studyflow.core.domain.materials.UploadPlanner
import dev.studyflow.core.domain.materials.UploadProgressStore
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState
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
        var current = materialRepository.observeById(materialId).first() ?: return UploadOutcome.MaterialMissing

        // Already synced — most likely a second run queued before the first one's success was
        // observed. Nothing to resend, and re-uploading would waste the user's data for no reason.
        if (current.sync == SyncState.Synced) return UploadOutcome.Synced

        val localPath = current.localPath
        if (localPath == null) {
            val reason = "material has no local file to upload"
            current.fail(reason, retryable = false)
            return UploadOutcome.Permanent(reason)
        }

        val plan = UploadPlanner.plan(current.sizeBytes, current.contentHash)

        return try {
            val request = UploadRequest.ofMaterial(current.contentHash, current.sizeBytes, current.mimeType)
            val session = objectStore.initUpload(request)
            val signedPartsByNumber = session.parts.associateBy { it.number }

            val completed = uploadProgressStore.completedParts(materialId).associateBy { it.number }.toMutableMap()
            current = current.markUploading(plan, completed.values)

            if (!plan.isComplete(completed.keys)) {
                for (part in plan.remaining(completed.keys)) {
                    val signedPart =
                        signedPartsByNumber[part.number]
                            ?: error("upload session for '${current.id}' has no signed URL for part ${part.number}")
                    val bytes = readPart(localPath, part)
                    val uploaded = objectStore.uploadPart(session, signedPart, bytes)
                    val completedPart = CompletedUploadPart(uploaded.number, uploaded.etag, uploaded.size)
                    uploadProgressStore.recordCompletedPart(materialId, completedPart)
                    completed[completedPart.number] = completedPart
                    current = current.markUploading(plan, completed.values)
                    onProgress(plan.uploadedBytes(completed.keys), plan.totalBytes)
                }
            }

            val orderedParts = plan.parts.map { part -> completed.getValue(part.number).asUploadedPart() }
            val stored = objectStore.completeUpload(session, orderedParts)

            uploadProgressStore.clear(materialId)
            materialRepository.save(current.copy(sync = SyncState.Synced, remoteKey = stored.key.value))
            UploadOutcome.Synced
        } catch (exception: ObjectStoreException) {
            val reason = exception.message ?: exception::class.simpleName.orEmpty()
            current.fail(reason, exception.retryable)
            if (exception.retryable) UploadOutcome.Retryable(reason) else UploadOutcome.Permanent(reason)
        } catch (exception: IOException) {
            // The local staging copy is gone or unreadable; resending the same bytes cannot help.
            val reason = "local file unreadable: ${exception.message}"
            current.fail(reason, retryable = false)
            UploadOutcome.Permanent(reason)
        }
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
