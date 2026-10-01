package dev.studyflow.core.scheduling

import android.content.SharedPreferences
import androidx.core.content.edit
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

/** Which synced materials' remote copies have already been confirmed to exist. */
public interface RemoteCopyLedger {
    public fun isVerified(
        materialId: String,
        remoteKey: String,
    ): Boolean

    public fun markVerified(
        materialId: String,
        remoteKey: String,
    )
}

/** [RemoteCopyLedger] in app-private preferences; cleared with app data, which only costs a re-check. */
public class SharedPreferencesRemoteCopyLedger(
    private val preferences: SharedPreferences,
) : RemoteCopyLedger {
    override fun isVerified(
        materialId: String,
        remoteKey: String,
    ): Boolean = preferences.getString(key(materialId), null) == remoteKey

    override fun markVerified(
        materialId: String,
        remoteKey: String,
    ) {
        preferences.edit { putString(key(materialId), remoteKey) }
    }

    private fun key(materialId: String): String = "verified.$materialId"
}

/**
 * Finds materials the catalogue calls [SyncState.Synced] whose remote object does not exist, and
 * puts them back on a path that ends in a real upload.
 *
 * Builds that bound the in-process object store marked materials synced, and pushed their
 * `remoteKey`s, for bytes that never left the device. Nothing about such a row looks wrong locally,
 * and every other device fails to download it. Each synced material is therefore `stat`ed once per
 * `remoteKey` — the [ledger] remembers which were found — and one that is missing is healed:
 *
 * - with its local file still on disk, it returns to [SyncState.Pending] and is re-queued;
 * - without one, nothing on this device can restore it, so it fails non-retryably with no
 *   `remoteKey`, which the catalogue presents as "re-attach or remove" ([Material.isMissingSource]).
 *
 * Neither state is pushed (only synced rows with a key are), so the server's row is corrected by the
 * re-upload itself: the content-addressed key it eventually reports is the same one it already has.
 */
public class MaterialRemoteVerifier(
    private val materialRepository: MaterialRepository,
    private val objectStore: ObjectStore,
    private val uploadCoordinator: MaterialUploadCoordinator,
    private val ledger: RemoteCopyLedger,
    private val logger: AppLogger? = null,
) {
    /**
     * Checks up to [limit] not-yet-verified synced materials, stopping early on a transient failure
     * (offline, rate-limited, an out-of-date storage service) so it never mistakes "could not ask"
     * for "not there".
     *
     * @return how many materials were healed.
     */
    public suspend fun verify(limit: Int = DEFAULT_LIMIT): Int {
        var healed = 0
        val healedKeys = mutableSetOf<String>()
        val candidates =
            materialRepository
                .observeAll()
                .first()
                .filter { it.sync == SyncState.Synced }
                .mapNotNull { material -> material.remoteKey?.let { material to it } }
                .filterNot { (material, key) -> ledger.isVerified(material.id, key) }
                .take(limit)
        for ((material, remoteKey) in candidates) {
            // A twin of a row already healed this run went back with it; asking again is wasted.
            val copy = if (remoteKey in healedKeys) RemoteCopy.Unknown else probe(remoteKey)
            if (copy == RemoteCopy.Unreachable) break
            if (copy == RemoteCopy.Missing) {
                healed += heal(material)
                healedKeys += remoteKey
            } else if (copy == RemoteCopy.Present) {
                ledger.markVerified(material.id, remoteKey)
            }
        }
        return healed
    }

    private suspend fun probe(remoteKey: String): RemoteCopy {
        // A key this client could never have written cannot be fetched either; it reads as missing.
        val key = runCatching { ObjectKey(remoteKey) }.getOrNull() ?: return RemoteCopy.Missing
        return try {
            if (objectStore.stat(key) == null) RemoteCopy.Missing else RemoteCopy.Present
        } catch (failure: ObjectStoreException) {
            if (failure.retryable) RemoteCopy.Unreachable else RemoteCopy.Unknown
        }
    }

    private enum class RemoteCopy { Present, Missing, Unknown, Unreachable }

    /**
     * Heals [materialId] after something else — a download — found its remote object missing. A
     * material that is no longer synced is left alone: it is already on its way back.
     */
    public suspend fun onRemoteMissing(materialId: String) {
        val material = materialRepository.observeById(materialId).first() ?: return
        if (material.sync == SyncState.Synced) heal(material)
    }

    /**
     * Every synced row sharing the missing key is reset together: the upload coordinator adopts an
     * already-synced twin's key instead of uploading, and a twin left behind would hand the broken
     * key straight back.
     */
    private suspend fun heal(material: Material): Int {
        val missingKey = material.remoteKey
        val affected =
            materialRepository
                .observeAll()
                .first()
                .filter { it.sync == SyncState.Synced && it.remoteKey == missingKey }
        affected.forEach { reset(it) }
        affected.filter { it.localPath != null }.forEach { uploadCoordinator.enqueueUpload(it.id) }
        return affected.size
    }

    private suspend fun reset(material: Material) {
        logger?.materialTransfer(
            code = DiagnosticCode.MaterialUpload,
            materialId = material.id,
            details =
                MaterialTransferDetails(
                    stage = MaterialTransferStage.DEDUPE,
                    outcome = MaterialTransferOutcome.REMOTE_MISSING,
                    retryable = material.localPath != null,
                ),
        )
        val sync =
            if (material.localPath != null) {
                SyncState.Pending
            } else {
                SyncState.Failed(REASON_NO_SOURCE, retryable = false)
            }
        materialRepository.save(material.copy(sync = sync, remoteKey = null))
    }

    public companion object {
        /** Well inside the storage function's 60-per-minute `stat` limit, leaving room for uploads. */
        public const val DEFAULT_LIMIT: Int = 30

        internal const val REASON_NO_SOURCE: String = "remote copy missing and no local file to re-upload"
    }
}
