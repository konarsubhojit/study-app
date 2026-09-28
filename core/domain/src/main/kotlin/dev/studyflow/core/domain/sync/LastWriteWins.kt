package dev.studyflow.core.domain.sync

import kotlin.time.Instant

/**
 * The one conflict rule every replicated entity shares (ADR 0012), stated once so the session and
 * record streams — and the server, which applies the same comparison in SQL — cannot drift apart.
 *
 * The later `updatedAt` wins; on a tie the greater `deviceId` (code-point order) wins; on a tie of
 * both, a tombstone beats a live row so a stale copy of a deleted row cannot undo the delete.
 */
public object LastWriteWins {
    /** @return true when the candidate replaces the incumbent. */
    @Suppress("LongParameterList")
    public fun wins(
        candidateUpdatedAt: Instant,
        candidateDeviceId: String,
        candidateDeleted: Boolean,
        incumbentUpdatedAt: Instant,
        incumbentDeviceId: String,
        incumbentDeleted: Boolean,
    ): Boolean =
        when {
            candidateUpdatedAt != incumbentUpdatedAt -> candidateUpdatedAt > incumbentUpdatedAt

            candidateDeviceId != incumbentDeviceId -> candidateDeviceId > incumbentDeviceId

            // Same device, same instant: the two copies describe the same write — unless one is a
            // tombstone, and letting that win is what stops a delete being undone by a stale copy
            // of the row it deleted.
            else -> candidateDeleted && !incumbentDeleted
        }

    /** [wins] for two [SyncDocument]s. */
    public fun wins(
        candidate: SyncDocument,
        incumbent: SyncDocument,
    ): Boolean =
        wins(
            candidateUpdatedAt = candidate.updatedAt,
            candidateDeviceId = candidate.deviceId,
            candidateDeleted = candidate.deleted,
            incumbentUpdatedAt = incumbent.updatedAt,
            incumbentDeviceId = incumbent.deviceId,
            incumbentDeleted = incumbent.deleted,
        )
}
