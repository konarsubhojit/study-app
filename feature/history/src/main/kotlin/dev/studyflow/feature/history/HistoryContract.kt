package dev.studyflow.feature.history

import dev.studyflow.core.domain.result.UserMessage
import dev.studyflow.core.domain.session.HistoryRejection
import dev.studyflow.core.domain.session.SessionHistoryFilter
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.model.Subject
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlin.time.Instant

/**
 * A form the user is actively editing on top of the history list.
 *
 * Kept as one sealed hierarchy on [HistoryUiState] rather than four separate booleans, so the
 * screen can render "at most one of these is open" the same way the state models it.
 */
public sealed interface HistoryDialog {
    public data class Edit(
        val session: StudySession,
        val subjectId: String?,
        val note: String,
        val startedAt: Instant,
        val endedAt: Instant,
    ) : HistoryDialog

    public data class Split(
        val session: StudySession,
        val at: Instant,
    ) : HistoryDialog

    public data class Merge(
        val sessionIds: List<String>,
    ) : HistoryDialog

    public data class ManualEntry(
        val subjectId: String? = null,
        val note: String = "",
        val startedAt: Instant,
        val endedAt: Instant,
    ) : HistoryDialog
}

/** A delete the user can still take back, offered as an undo snackbar (issue #32). */
public data class HistoryUndo(
    val sessionIds: List<String>,
)

/**
 * Everything the history screen renders apart from the paged rows themselves.
 *
 * The paged rows are deliberately not part of this state: they are exposed by
 * [HistoryViewModel.pagedSessions] as their own `Flow<PagingData<StudySession>>` so that a filter
 * change or a selection toggle never forces Paging 3 to reload or re-diff a page it already has.
 */
public data class HistoryUiState(
    val filter: SessionHistoryFilter = SessionHistoryFilter(),
    val subjectOptions: List<Subject> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val dialog: HistoryDialog? = null,
    val undo: HistoryUndo? = null,
    val errorMessage: UserMessage? = null,
) : UiState {
    public val isSelecting: Boolean get() = selectedIds.isNotEmpty()
}

public sealed interface HistoryUiEvent : UiEvent {
    public data class SubjectFilterChanged(
        val subjectId: String?,
    ) : HistoryUiEvent

    public data class DateRangeFilterChanged(
        val from: Instant?,
        val to: Instant?,
    ) : HistoryUiEvent

    public data object FilterCleared : HistoryUiEvent

    public data class SelectionToggled(
        val sessionId: String,
    ) : HistoryUiEvent

    public data object SelectionCleared : HistoryUiEvent

    public data class SessionOpened(
        val session: StudySession,
    ) : HistoryUiEvent

    public data class DeleteRequested(
        val sessionId: String,
    ) : HistoryUiEvent

    public data object BulkDeleteRequested : HistoryUiEvent

    public data object UndoRequested : HistoryUiEvent

    public data object UndoDismissed : HistoryUiEvent

    public data class SplitRequested(
        val session: StudySession,
    ) : HistoryUiEvent

    public data class SplitInstantChanged(
        val at: Instant,
    ) : HistoryUiEvent

    public data object SplitConfirmed : HistoryUiEvent

    public data object MergeRequested : HistoryUiEvent

    public data object MergeConfirmed : HistoryUiEvent

    public data object ManualEntryRequested : HistoryUiEvent

    public data class ManualEntrySubjectChanged(
        val subjectId: String?,
    ) : HistoryUiEvent

    public data class ManualEntryNoteChanged(
        val note: String,
    ) : HistoryUiEvent

    public data class ManualEntryTimingChanged(
        val startedAt: Instant,
        val endedAt: Instant,
    ) : HistoryUiEvent

    public data object ManualEntryConfirmed : HistoryUiEvent

    public data class EditSubjectChanged(
        val subjectId: String?,
    ) : HistoryUiEvent

    public data class EditNoteChanged(
        val note: String,
    ) : HistoryUiEvent

    public data class EditTimingChanged(
        val startedAt: Instant,
        val endedAt: Instant,
    ) : HistoryUiEvent

    public data object EditConfirmed : HistoryUiEvent

    public data object DialogDismissed : HistoryUiEvent

    public data object ErrorMessageDismissed : HistoryUiEvent
}

public sealed interface HistoryUiEffect : UiEffect

/** Turns a rejection into copy a user can act on, rather than a raw enum name. */
internal fun HistoryRejection.toUserMessage(): UserMessage =
    when (this) {
        HistoryRejection.SESSION_NOT_FOUND, HistoryRejection.NOTHING_TO_UNDO -> UserMessage.Unknown
        HistoryRejection.SESSION_DELETED -> UserMessage.Validation
        HistoryRejection.SESSION_NOT_STOPPED -> UserMessage.Validation
        HistoryRejection.INVALID_TIMING -> UserMessage.Validation
        HistoryRejection.SPLIT_POINT_OUT_OF_BOUNDS -> UserMessage.Validation
        HistoryRejection.MERGE_REQUIRES_AT_LEAST_TWO_SESSIONS -> UserMessage.Validation
        HistoryRejection.MERGE_SUBJECT_MISMATCH -> UserMessage.Validation
        HistoryRejection.MERGE_TASK_MISMATCH -> UserMessage.Validation
        HistoryRejection.MERGE_DEVICE_MISMATCH -> UserMessage.Validation
    }
