package dev.studyflow.core.scheduling

import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.common.logging.DiagnosticKey
import dev.studyflow.core.common.logging.diagnosticEvent
import java.util.UUID

internal enum class MaterialTransferStage {
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
}

internal fun AppLogger.materialTransfer(
    code: DiagnosticCode,
    materialId: String,
    stage: MaterialTransferStage,
    outcome: MaterialTransferOutcome,
    retryable: Boolean,
    part: Int? = null,
    partCount: Int? = null,
    throwable: Throwable? = null,
) {
    diagnostic(
        diagnosticEvent(code) {
            put(DiagnosticKey.Stage, stage)
            put(DiagnosticKey.Outcome, outcome)
            put(DiagnosticKey.Retryable, retryable)
            runCatching { UUID.fromString(materialId) }
                .getOrNull()
                ?.takeIf { it.toString().equals(materialId, ignoreCase = true) }
                ?.let { put(DiagnosticKey.MaterialId, it) }
            part?.let { put(DiagnosticKey.Part, it) }
            partCount?.let { put(DiagnosticKey.PartCount, it) }
        },
        throwable,
    )
}
