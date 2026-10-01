package dev.studyflow.feature.materials

import dev.studyflow.core.domain.materials.ImportFailureReason
import dev.studyflow.core.domain.materials.ImportRejectionReason
import dev.studyflow.core.domain.materials.UploadWaitReason
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState

/**
 * The words for the import outcomes [dev.studyflow.core.domain.materials.MaterialImporter] hands
 * back.
 *
 * Kept in one object rather than inline in the view model or the composables so that the day this
 * app ships a second language, the move to `strings.xml` is a mechanical edit of this file alone.
 */
internal object MaterialsCopy {
    fun rejection(reason: ImportRejectionReason): String =
        when (reason) {
            ImportRejectionReason.FILE_TOO_LARGE -> {
                "This file is too large to add. Files can be up to 50 MB."
            }

            ImportRejectionReason.BLOCKED_TYPE -> {
                "This file type isn't supported in your materials library."
            }

            ImportRejectionReason.UNSUPPORTED_TYPE -> {
                "Only PDFs, images, plain text and MP4 videos can be added and synced."
            }

            ImportRejectionReason.EMPTY_FILE -> {
                "This file is empty, so there is nothing to add."
            }
        }

    fun failure(reason: ImportFailureReason): String =
        when (reason) {
            ImportFailureReason.UNREADABLE -> {
                "StudyFlow couldn't read this file. It may have been moved, deleted, or never granted access."
            }

            ImportFailureReason.CANCELLED -> {
                "Import was cancelled."
            }
        }

    const val REATTACH_MISMATCH: String =
        "That isn't the same file. Choose the original, or add this one as a new material."

    /**
     * One catalogue cell's sync line, or `null` once it is synced. A pending material held back by
     * [waitReason] says what it is waiting for, so a stalled upload never reads like a fault.
     */
    fun uploadStatus(
        material: Material,
        waitReason: UploadWaitReason?,
    ): String? =
        when (material.sync) {
            SyncState.Pending -> waitReason?.let(::waiting) ?: "Pending upload"
            is SyncState.Uploading -> "Uploading"
            is SyncState.Failed -> if (material.isMissingSource) "File missing" else "Upload failed"
            SyncState.Synced -> null
        }

    fun waiting(reason: UploadWaitReason): String =
        when (reason) {
            UploadWaitReason.WAITING_FOR_WIFI -> "Waiting for Wi-Fi"
            UploadWaitReason.WAITING_FOR_NETWORK -> "Waiting for a connection"
            UploadWaitReason.WAITING_FOR_BATTERY -> "Waiting for battery"
        }

    /** The notice above the grid explaining a wait; only Wi-Fi-only is the user's to change. */
    fun waitingNotice(reason: UploadWaitReason): String =
        when (reason) {
            UploadWaitReason.WAITING_FOR_WIFI -> {
                "Uploads are set to Wi-Fi only, so files will upload when you connect to Wi-Fi."
            }

            UploadWaitReason.WAITING_FOR_NETWORK -> {
                "You're offline. Files will upload when you're back online."
            }

            UploadWaitReason.WAITING_FOR_BATTERY -> {
                "Your battery is low. Files will upload once it's charged."
            }
        }
}
