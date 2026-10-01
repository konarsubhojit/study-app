package dev.studyflow.core.scheduling

import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.common.logging.DiagnosticKey
import dev.studyflow.core.common.logging.diagnosticEvent
import java.util.UUID

internal enum class MaterialTransferStage {
    /**
     * The "has anyone already stored these bytes?" probe that runs before planning.
     *
     * Its own stage rather than `VERIFY`: a `VERIFY outcome=NOT_FOUND` line logged *before*
     * `PLAN outcome=SUCCESS` read as a transfer verifying something it had not sent yet.
     */
    DEDUPE,
    PLAN,
    INIT,
    PART,
    COMPLETE,
    VERIFY,

    /** A catalogue row said `Synced` but the store holds nothing; see `MaterialStorageReconciler`. */
    REPAIR,
}

internal enum class MaterialTransferOutcome {
    STARTED,
    SUCCESS,
    FAILURE,
    SKIPPED,
    NOT_FOUND,
    MISMATCH,
}

internal data class MaterialTransferDetails(
    val stage: MaterialTransferStage,
    val outcome: MaterialTransferOutcome,
    val retryable: Boolean,
    val part: Int? = null,
    val partCount: Int? = null,
)

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
            runCatching { UUID.fromString(materialId) }
                .getOrNull()
                ?.takeIf { it.toString().equals(materialId, ignoreCase = true) }
                ?.let { put(DiagnosticKey.MaterialId, it) }
            details.part?.let { put(DiagnosticKey.Part, it) }
            details.partCount?.let { put(DiagnosticKey.PartCount, it) }
        },
        throwable,
    )
}
