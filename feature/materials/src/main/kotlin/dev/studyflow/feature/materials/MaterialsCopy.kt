package dev.studyflow.feature.materials

import dev.studyflow.core.domain.materials.ImportFailureReason
import dev.studyflow.core.domain.materials.ImportRejectionReason
import dev.studyflow.core.domain.materials.UploadWaitReason

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
                "This file is larger than StudyFlow allows for its type."
            }

            ImportRejectionReason.BLOCKED_TYPE, ImportRejectionReason.UNSUPPORTED_TYPE -> {
                "This file type isn't supported in your materials library."
            }

            ImportRejectionReason.EMPTY_FILE -> {
                "This file is empty, so there's nothing to add."
            }
        }

    /**
     * Why a queued upload has not started, in plain language (ADR 0014).
     *
     * No status code, no exception text, no mention of WorkManager: the user can act on "waiting
     * for Wi-Fi" and on nothing else, and this is the sentence that separates a device behaving
     * exactly as configured from a device that is broken.
     */
    fun waiting(reason: UploadWaitReason): String =
        when (reason) {
            UploadWaitReason.WAITING_FOR_WIFI -> "Waiting for Wi-Fi"
            UploadWaitReason.WAITING_FOR_NETWORK -> "Waiting for a connection"
            UploadWaitReason.WAITING_FOR_BATTERY -> "Waiting until the battery recovers"
        }

    /** The banner above the grid, which explains the wait once rather than once per material. */
    fun waitingExplanation(reason: UploadWaitReason): String =
        when (reason) {
            UploadWaitReason.WAITING_FOR_WIFI ->
                "Your files upload on Wi-Fi only, so they'll be sent as soon as you join a Wi-Fi network."

            UploadWaitReason.WAITING_FOR_NETWORK ->
                "Your files will be sent as soon as this device is back online."

            UploadWaitReason.WAITING_FOR_BATTERY ->
                "Your files will be sent once the battery is charged a little more."
        }

    /** A material with no copy left anywhere: retrying cannot help, so it is never offered. */
    const val MISSING_SOURCE: String = "This file is no longer on this device. Add it again, or remove it."

    fun failure(reason: ImportFailureReason): String =
        when (reason) {
            ImportFailureReason.UNREADABLE -> {
                "StudyFlow couldn't read this file. It may have been moved, deleted, or never granted access."
            }

            ImportFailureReason.CANCELLED -> {
                "Import was cancelled."
            }
        }
}
