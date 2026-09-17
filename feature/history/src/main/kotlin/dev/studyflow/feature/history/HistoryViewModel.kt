package dev.studyflow.feature.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.DeviceIdProvider
import dev.studyflow.core.domain.result.UserMessage
import dev.studyflow.core.domain.session.SessionHistoryCommandResult
import dev.studyflow.core.domain.session.SessionHistoryFilter
import dev.studyflow.core.domain.session.SessionHistoryRepository
import dev.studyflow.core.domain.subjects.SubjectRepository
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.ui.mvi.MviViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Drives the history screen: paged browsing, filtering, bulk selection, and every correction the
 * editor screen can request (issue #32).
 *
 * Each correction is one call into [repository], which is itself a thin wrapper over the pure
 * `SessionHistoryEditor` — this class only turns UI intents into those calls and the result back
 * into dialog/undo/error state; it holds no correction rules of its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
public class HistoryViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: SessionHistoryRepository,
        private val subjectRepository: SubjectRepository,
        private val clock: Clock,
        private val deviceIdProvider: DeviceIdProvider,
    ) : MviViewModel<HistoryUiEvent, HistoryUiEffect>(savedStateHandle) {
        private val filter = MutableStateFlow(SessionHistoryFilter())
        private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
        private val dialog = MutableStateFlow<HistoryDialog?>(null)
        private val undo = MutableStateFlow<HistoryUndo?>(null)
        private val errorMessage = MutableStateFlow<UserMessage?>(null)

        /**
         * The paged rows themselves, kept out of [state] so a filter change replaces this flow
         * (via `flatMapLatest`) instead of the combined UI state re-emitting on every keystroke of
         * an unrelated field.
         */
        public val pagedSessions: Flow<PagingData<StudySession>> =
            filter
                .flatMapLatest { current ->
                    Pager(PagingConfig(pageSize = PAGE_SIZE)) { repository.historyPagingSource(current) }.flow
                }.cachedIn(viewModelScope)

        public val state: StateFlow<HistoryUiState> =
            combine(
                combine(filter, subjectRepository.observeSubjects()) { currentFilter, subjects ->
                    currentFilter to subjects
                },
                selectedIds,
                dialog,
                undo,
                errorMessage,
            ) { (currentFilter, subjects), selection, currentDialog, currentUndo, error ->
                HistoryUiState(
                    filter = currentFilter,
                    subjectOptions = subjects,
                    selectedIds = selection,
                    dialog = currentDialog,
                    undo = currentUndo,
                    errorMessage = error,
                )
            }.stateInViewModel(HistoryUiState())

        override fun onEvent(event: HistoryUiEvent) {
            when (event) {
                is HistoryUiEvent.SubjectFilterChanged -> {
                    filter.value = filter.value.copy(subjectId = event.subjectId)
                }

                is HistoryUiEvent.DateRangeFilterChanged -> {
                    filter.value = filter.value.copy(from = event.from, to = event.to)
                }

                HistoryUiEvent.FilterCleared -> {
                    filter.value = SessionHistoryFilter()
                }

                is HistoryUiEvent.SelectionToggled -> {
                    toggleSelection(event.sessionId)
                }

                HistoryUiEvent.SelectionCleared -> {
                    selectedIds.value = emptySet()
                }

                is HistoryUiEvent.SessionOpened -> {
                    dialog.value = event.session.asEditDialog()
                }

                is HistoryUiEvent.DeleteRequested -> {
                    delete(listOf(event.sessionId))
                }

                HistoryUiEvent.BulkDeleteRequested -> {
                    delete(selectedIds.value.toList())
                }

                HistoryUiEvent.UndoRequested -> {
                    undoLastDeletion()
                }

                HistoryUiEvent.UndoDismissed -> {
                    undo.value = null
                }

                is HistoryUiEvent.SplitRequested -> {
                    val midpoint = event.session.endedAt?.let { midpointOf(event.session.startedAt, it) }
                    dialog.value = midpoint?.let { HistoryDialog.Split(event.session, it) }
                }

                is HistoryUiEvent.SplitInstantChanged -> {
                    (dialog.value as? HistoryDialog.Split)?.let { current ->
                        dialog.value = current.copy(at = event.at)
                    }
                }

                HistoryUiEvent.SplitConfirmed -> {
                    confirmSplit()
                }

                HistoryUiEvent.MergeRequested -> {
                    if (selectedIds.value.size >= MIN_MERGE_SESSIONS) {
                        dialog.value = HistoryDialog.Merge(selectedIds.value.toList())
                    }
                }

                HistoryUiEvent.MergeConfirmed -> {
                    confirmMerge()
                }

                HistoryUiEvent.ManualEntryRequested -> {
                    val now = clock.now()
                    dialog.value = HistoryDialog.ManualEntry(startedAt = now - DEFAULT_MANUAL_ENTRY_LENGTH, endedAt = now)
                }

                is HistoryUiEvent.ManualEntrySubjectChanged -> {
                    (dialog.value as? HistoryDialog.ManualEntry)?.let { current ->
                        dialog.value = current.copy(subjectId = event.subjectId)
                    }
                }

                is HistoryUiEvent.ManualEntryNoteChanged -> {
                    (dialog.value as? HistoryDialog.ManualEntry)?.let { current ->
                        dialog.value = current.copy(note = event.note)
                    }
                }

                is HistoryUiEvent.ManualEntryTimingChanged -> {
                    (dialog.value as? HistoryDialog.ManualEntry)?.let { current ->
                        dialog.value = current.copy(startedAt = event.startedAt, endedAt = event.endedAt)
                    }
                }

                HistoryUiEvent.ManualEntryConfirmed -> {
                    confirmManualEntry()
                }

                is HistoryUiEvent.EditSubjectChanged -> {
                    (dialog.value as? HistoryDialog.Edit)?.let { current ->
                        dialog.value = current.copy(subjectId = event.subjectId)
                    }
                }

                is HistoryUiEvent.EditNoteChanged -> {
                    (dialog.value as? HistoryDialog.Edit)?.let { current ->
                        dialog.value = current.copy(note = event.note)
                    }
                }

                is HistoryUiEvent.EditTimingChanged -> {
                    (dialog.value as? HistoryDialog.Edit)?.let { current ->
                        dialog.value = current.copy(startedAt = event.startedAt, endedAt = event.endedAt)
                    }
                }

                HistoryUiEvent.EditConfirmed -> {
                    confirmEdit()
                }

                HistoryUiEvent.DialogDismissed -> {
                    dialog.value = null
                }

                HistoryUiEvent.ErrorMessageDismissed -> {
                    errorMessage.value = null
                }
            }
        }

        private fun toggleSelection(sessionId: String) {
            selectedIds.value =
                if (sessionId in selectedIds.value) {
                    selectedIds.value - sessionId
                } else {
                    selectedIds.value + sessionId
                }
        }

        private fun confirmEdit() {
            val edit = dialog.value as? HistoryDialog.Edit ?: return
            val session = edit.session
            viewModelScope.launch {
                if (edit.startedAt != session.startedAt || edit.endedAt != session.endedAt) {
                    val result = repository.editTiming(session.id, edit.startedAt, edit.endedAt, newId(), clock.now())
                    if (!result.handle()) return@launch
                }
                if (edit.subjectId != session.subjectId) {
                    val result = repository.editSubject(session.id, edit.subjectId, newId(), clock.now())
                    if (!result.handle()) return@launch
                }
                if (edit.note != (session.note ?: "")) {
                    val result = repository.editNote(session.id, edit.note.ifBlank { null }, newId(), clock.now())
                    if (!result.handle()) return@launch
                }
                dialog.value = null
            }
        }

        private fun confirmSplit() {
            val split = dialog.value as? HistoryDialog.Split ?: return
            viewModelScope.launch {
                val result = repository.split(split.session.id, split.at, newId(), newId(), clock.now())
                if (result.handle()) dialog.value = null
            }
        }

        private fun confirmMerge() {
            val merge = dialog.value as? HistoryDialog.Merge ?: return
            viewModelScope.launch {
                val result = repository.merge(merge.sessionIds, newId(), clock.now())
                if (result.handle()) {
                    dialog.value = null
                    selectedIds.value = emptySet()
                }
            }
        }

        private fun confirmManualEntry() {
            val entry = dialog.value as? HistoryDialog.ManualEntry ?: return
            viewModelScope.launch {
                val result =
                    repository.manualEntry(
                        deviceId = deviceIdProvider.current(),
                        subjectId = entry.subjectId,
                        note = entry.note.ifBlank { null },
                        startedAt = entry.startedAt,
                        endedAt = entry.endedAt,
                        sessionId = newId(),
                        correctionId = newId(),
                        now = clock.now(),
                    )
                if (result.handle()) dialog.value = null
            }
        }

        private fun delete(sessionIds: List<String>) {
            if (sessionIds.isEmpty()) return
            viewModelScope.launch {
                val result =
                    if (sessionIds.size == 1) {
                        repository.delete(sessionIds.single(), newId(), clock.now())
                    } else {
                        repository.bulkDelete(sessionIds, { newId() }, clock.now())
                    }
                if (result.handle()) {
                    undo.value = HistoryUndo(sessionIds)
                    selectedIds.value = emptySet()
                }
            }
        }

        private fun undoLastDeletion() {
            val pending = undo.value ?: return
            viewModelScope.launch {
                pending.sessionIds.forEach { sessionId -> repository.undo(sessionId, newId(), clock.now()) }
                undo.value = null
            }
        }

        /** Reports [this] against the shared error/dialog state; returns whether the call applied. */
        private fun SessionHistoryCommandResult.handle(): Boolean =
            when (this) {
                is SessionHistoryCommandResult.Applied -> true

                is SessionHistoryCommandResult.Rejected -> {
                    errorMessage.value = reason.toUserMessage()
                    false
                }

                is SessionHistoryCommandResult.Failed -> {
                    errorMessage.value = error.message
                    false
                }
            }

        private fun newId(): String = UUID.randomUUID().toString()

        private companion object {
            const val PAGE_SIZE = 30
            const val MIN_MERGE_SESSIONS = 2
            val DEFAULT_MANUAL_ENTRY_LENGTH = 30.minutes

            fun StudySession.asEditDialog(): HistoryDialog.Edit =
                HistoryDialog.Edit(
                    session = this,
                    subjectId = subjectId,
                    note = note ?: "",
                    startedAt = startedAt,
                    endedAt = endedAt ?: startedAt,
                )

            fun midpointOf(
                startedAt: Instant,
                endedAt: Instant,
            ): Instant = startedAt + (endedAt - startedAt) / 2
        }
    }
