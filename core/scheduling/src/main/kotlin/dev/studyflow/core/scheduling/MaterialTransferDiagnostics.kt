package dev.studyflow.core.scheduling

import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.common.logging.DiagnosticFields
import dev.studyflow.core.common.logging.DiagnosticKey
import dev.studyflow.core.common.logging.diagnosticEvent
import java.util.UUID

internal enum class MaterialTransferStage {
    /** The upload was handed to WorkManager; logged before any constraint can hold it back. */
    ENQUEUE,

    /**
     * The `stat` probe before a transfer: is the object already stored, so nothing need be sent?
     * Also how a [dev.studyflow.core.model.SyncState.Synced] material's remote copy is re-checked.
     */
    DEDUPE,
    PLAN,
    INIT,
    PART,
    COMPLETE,
    VERIFY,
}

internal enum class MaterialTransferOutcome {
    STARTED,
    SUCCESS,
    FAILURE,
    SKIPPED,
    NOT_FOUND,
    MISMATCH,

    /** Work was queued; whether it runs now depends on the logged constraint. */
    QUEUED,

    /** A material the catalogue believed synced has no remote object; it is being re-uploaded. */
    REMOTE_MISSING,
}

internal data class MaterialTransferDetails(
    val stage: MaterialTransferStage,
    val outcome: MaterialTransferOutcome,
    val retryable: Boolean,
    val part: Int? = null,
    val partCount: Int? = null,
)

/**
 * Records that [materialId]'s upload was enqueued, and under which constraint.
 *
 * A request whose constraint is unmet never reaches `doWork()`, so without this line an export
 * cannot tell "never enqueued" from "enqueued and waiting for Wi-Fi".
 */
internal fun AppLogger.materialUploadEnqueued(
    materialId: String,
    networkType: Enum<*>,
    syncMode: Enum<*>,
) {
    diagnostic(
        diagnosticEvent(DiagnosticCode.MaterialUpload) {
            put(DiagnosticKey.Stage, MaterialTransferStage.ENQUEUE)
            put(DiagnosticKey.Outcome, MaterialTransferOutcome.QUEUED)
            putMaterialId(materialId)
            put(DiagnosticKey.NetworkType, networkType)
            put(DiagnosticKey.SyncMode, syncMode)
        },
    )
}

internal fun AppLogger.materialTransfer(
    code: DiagnosticCode,
    materialId: String,
    details: MaterialTransferDetails,
    throwable: Throwable? = null,
) {
    diagnostic(
        diagnosticEvent(code) {
            put(DiagnosticKey.Stage, details.stage)
            put(DiagnosticKey.Outcome, details.outcome)
            put(DiagnosticKey.Retryable, details.retryable)
            putMaterialId(materialId)
            details.part?.let { put(DiagnosticKey.Part, it) }
            details.partCount?.let { put(DiagnosticKey.PartCount, it) }
        },
        throwable,
    )
}

/** Only a well-formed UUID is logged: an id is never a display name or a path. */
private fun DiagnosticFields.putMaterialId(materialId: String) {
    runCatching { UUID.fromString(materialId) }
        .getOrNull()
        ?.takeIf { it.toString().equals(materialId, ignoreCase = true) }
        ?.let { put(DiagnosticKey.MaterialId, it) }
}
