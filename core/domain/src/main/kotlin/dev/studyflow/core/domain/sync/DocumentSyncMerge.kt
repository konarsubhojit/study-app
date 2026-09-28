package dev.studyflow.core.domain.sync

import dev.studyflow.core.model.Material
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.SyncState
import kotlin.time.Instant

/** What merging one inbound [SyncDocument] into the local copy decided. */
public sealed interface DocumentMergeOutcome {
    /** The change must not be stored: it is malformed for its type, or cannot be used here. */
    public data class Rejected(
        val reason: String,
    ) : DocumentMergeOutcome

    /** The local copy beats the inbound one on [LastWriteWins]; nothing is written. */
    public data object KeptLocal : DocumentMergeOutcome

    /** The inbound copy won; [document] is what this device stores. */
    public data class Replaced(
        val document: SyncDocument,
    ) : DocumentMergeOutcome
}

/**
 * The conflict policy for tasks and materials, as a pure function of the two copies (ADR 0018).
 *
 * 1. **Whole-record last-write-wins** via [LastWriteWins]. A task travels with its checklist,
 *    tags, recurrence and reminders as one aggregate, so two devices never end up with half of
 *    each other's edit.
 * 2. **Device-local state is never replicated.** Whichever copy wins, the receiving device keeps
 *    its own reminder alarms, snoozes and fired markers, and its own downloaded file, offline pin
 *    and preview position.
 * 3. **A reminder never fires for a trigger that passed before the device learned of it.** A
 *    received reminder's fired marker is raised to the moment it arrived, so a task synced to a new
 *    device does not replay every reminder the first device already delivered.
 */
public object DocumentSyncMerge {
    /**
     * Whether a material may cross the wire: only once its bytes are in the object store, because a
     * catalogue row whose file no other device can fetch is not something a second device can use.
     */
    public fun isSyncable(material: Material): Boolean = material.remoteKey != null && material.sync == SyncState.Synced

    /**
     * Merges [remote] into [local].
     *
     * @param local the copy this device holds, or `null` when it has never seen the entity.
     * @param receivedAt when the change arrived; see rule 3.
     */
    public fun merge(
        local: SyncDocument?,
        remote: SyncDocument,
        receivedAt: Instant,
    ): DocumentMergeOutcome {
        if (local != null && local.entityType != remote.entityType) {
            return DocumentMergeOutcome.Rejected(
                "${remote.entityType} ${remote.id} collides with a local ${local.entityType}",
            )
        }
        if (local != null && !LastWriteWins.wins(candidate = remote, incumbent = local)) {
            return DocumentMergeOutcome.KeptLocal
        }
        return when (remote) {
            is SyncTaskRecord -> {
                DocumentMergeOutcome.Replaced(
                    remote.copy(task = mergeTask((local as SyncTaskRecord?)?.task, remote.task, receivedAt)),
                )
            }

            is SyncMaterialRecord -> {
                if (remote.material.remoteKey == null) {
                    DocumentMergeOutcome.Rejected("material ${remote.id} has no uploaded object")
                } else {
                    DocumentMergeOutcome.Replaced(
                        remote.copy(
                            material = mergeMaterial((local as SyncMaterialRecord?)?.material, remote.material),
                        ),
                    )
                }
            }
        }
    }

    private fun mergeTask(
        local: StudyTask?,
        remote: StudyTask,
        receivedAt: Instant,
    ): StudyTask {
        val localReminders = local?.reminders.orEmpty().associateBy { it.id }
        return remote.copy(
            reminders =
                remote.reminders.map { reminder ->
                    val known = localReminders[reminder.id]
                    reminder.copy(
                        snooze = known?.snooze,
                        schedulingId = known?.schedulingId,
                        lastFiredAt = maxOf(known?.lastFiredAt ?: receivedAt, receivedAt),
                    )
                },
        )
    }

    private fun mergeMaterial(
        local: Material?,
        remote: Material,
    ): Material =
        remote.copy(
            sync = SyncState.Synced,
            // The downloaded file only still describes the record if the bytes are the same ones.
            localPath = local?.localPath?.takeIf { local.contentHash == remote.contentHash },
            pinnedForOffline = local?.pinnedForOffline ?: false,
            previewPageIndex = local?.previewPageIndex ?: 0,
            previewPositionMillis = local?.previewPositionMillis ?: 0,
            playbackSpeed = local?.playbackSpeed ?: 1f,
        )
}
