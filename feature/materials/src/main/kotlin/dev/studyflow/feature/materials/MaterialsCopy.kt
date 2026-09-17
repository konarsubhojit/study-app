package dev.studyflow.feature.materials

import dev.studyflow.core.domain.materials.ImportFailureReason
import dev.studyflow.core.domain.materials.ImportRejectionReason

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

            ImportRejectionReason.BLOCKED_TYPE -> {
                "This file type isn't supported in your materials library."
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
}
