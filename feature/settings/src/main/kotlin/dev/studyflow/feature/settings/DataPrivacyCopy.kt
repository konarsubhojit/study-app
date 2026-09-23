package dev.studyflow.feature.settings

import dev.studyflow.core.domain.lifecycle.AccountDeletionReceipt
import dev.studyflow.core.domain.result.DomainError

/**
 * The words the data & privacy screen uses (issue #78).
 *
 * Kept in one object, like [NotificationCopy], so the day this app ships a second language the
 * move to `strings.xml` is a mechanical edit of this file and nothing else. The retention window
 * is interpolated from [AccountDeletionReceipt] rather than typed into a sentence, because a
 * promise that differs between the screen and the server is worse than no promise at all.
 */
internal object DataPrivacyCopy {
    const val TITLE = "Data & privacy"
    const val BUSY = "Working"
    const val DISMISS = "Dismiss"
    const val CANCEL = "Cancel"

    const val EXPORT_TITLE = "Export your data"
    const val EXPORT_BODY =
        "Saves everything — sessions, tasks, subjects and your material files — into one archive " +
            "you choose the location of. You can restore it on another device."
    const val EXPORT_ACTION = "Export"

    const val IMPORT_TITLE = "Restore from an archive"
    const val IMPORT_BODY =
        "Merges an archive back in. Importing the same archive twice changes nothing, and anything " +
            "you have edited more recently on this device is kept."
    const val IMPORT_ACTION = "Import"

    const val DELETE_TITLE = "Delete your account"
    const val DELETE_ACTION = "Delete account and data"
    const val DELETE_CONFIRM_TITLE = "Delete everything?"
    const val DELETE_CONFIRM_ACTION = "Delete permanently"

    fun deleteBody(retentionWindowDays: Int = AccountDeletionReceipt.DEFAULT_RETENTION_WINDOW_DAYS): String =
        "Removes your account and its files from our servers, then clears everything on this " +
            "device. Backups are purged within $retentionWindowDays days."

    fun deleteConfirmBody(retentionWindowDays: Int = AccountDeletionReceipt.DEFAULT_RETENTION_WINDOW_DAYS): String =
        "Your sessions, tasks, subjects and materials will be deleted from this device and from " +
            "our servers, and the last backup copy is purged within $retentionWindowDays days. " +
            "This cannot be undone. Export first if you want to keep a copy."

    fun describe(message: DataPrivacyMessage): String =
        when (message) {
            is DataPrivacyMessage.Exported -> describeExport(message)
            is DataPrivacyMessage.Imported -> describeImport(message)
            is DataPrivacyMessage.AccountDeleted -> describeDeletion(message)
            is DataPrivacyMessage.Failed -> describeFailure(message)
        }

    private fun describeExport(message: DataPrivacyMessage.Exported): String =
        buildString {
            append("Exported ${message.records} records and ${message.materialFiles} files.")
            if (message.materialsWithoutBytes > 0) {
                append(
                    " ${message.materialsWithoutBytes} material(s) were not downloaded on this " +
                        "device, so only their details were saved.",
                )
            }
        }

    private fun describeImport(message: DataPrivacyMessage.Imported): String =
        buildString {
            append("Restored ${message.added} new records and ${message.restoredFiles} files.")
            if (message.updated > 0) append(" ${message.updated} were updated.")
            if (message.unchanged > 0) append(" ${message.unchanged} were already up to date.")
            if (message.rejectedEntries > 0) {
                append(" ${message.rejectedEntries} entries in the archive were unsafe and were skipped.")
            }
        }

    private fun describeDeletion(message: DataPrivacyMessage.AccountDeleted): String =
        buildString {
            append("Your account is deleted. Backups are purged within ${message.retentionWindowDays} days.")
            if (message.remaining.isNotEmpty()) {
                append(
                    " Some data could not be cleared on this device (${message.remaining.joinToString()}). " +
                        "Clear StudyFlow's app data in system settings to finish.",
                )
            }
        }

    private fun describeFailure(message: DataPrivacyMessage.Failed): String {
        val what =
            when (message.task) {
                DataPrivacyTask.EXPORT -> "The export did not finish."
                DataPrivacyTask.IMPORT -> "The archive could not be restored."
                DataPrivacyTask.DELETE -> "Your account was not deleted, and nothing on this device was cleared."
            }
        val why =
            when (message.error) {
                DomainError.Network -> "Check your connection and try again."
                DomainError.Storage -> "There was not enough space, or the file could not be written."
                DomainError.Permission -> "StudyFlow is not allowed to use that location."
                DomainError.Validation -> "That file is not a StudyFlow archive."
                DomainError.Unknown -> "Please try again."
            }
        return "$what $why"
    }
}
