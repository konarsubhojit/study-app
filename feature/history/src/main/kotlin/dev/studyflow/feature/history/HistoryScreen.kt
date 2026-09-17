package dev.studyflow.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.domain.result.UserMessage
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.model.Subject
import dev.studyflow.core.ui.components.StudyFlowDialog
import dev.studyflow.core.ui.components.StudyFlowListItem
import dev.studyflow.core.ui.components.StudyFlowTopAppBar
import dev.studyflow.core.ui.state.EmptyState
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The history screen: paged, filterable browsing plus every correction (issue #32).
 *
 * The day grouping and per-subject totals are computed here, from whatever page of
 * [HistoryViewModel.pagedSessions] is currently loaded, rather than in a Paging 3 separator: a
 * day's total needs every session in that day, and Paging streams pages incrementally, so a
 * separator inserted mid-stream cannot see totals for a day that is still being fetched. Grouping
 * client-side over the already-loaded snapshot keeps the total both correct and cheap, matching
 * [dev.studyflow.core.domain.session.SessionHistoryRepository.dailyTotals]'s own documented intent.
 */
@Composable
public fun HistoryRoute(
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagedSessions = viewModel.pagedSessions.collectAsLazyPagingItems()

    HistoryScreen(
        state = state,
        pagedSessions = pagedSessions,
        onEvent = viewModel::onEvent,
        modifier = modifier,
    )
}

@Composable
public fun HistoryScreen(
    state: HistoryUiState,
    pagedSessions: androidx.paging.compose.LazyPagingItems<StudySession>,
    onEvent: (HistoryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.undo) {
        if (state.undo != null) {
            val result = snackbarHostState.showSnackbar(message = "Deleted", actionLabel = "Undo")
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                onEvent(HistoryUiEvent.UndoRequested)
            } else {
                onEvent(HistoryUiEvent.UndoDismissed)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            StudyFlowTopAppBar(
                title = if (state.isSelecting) "${state.selectedIds.size} selected" else "History",
                actions = { HistoryTopBarActions(state = state, onEvent = onEvent) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEvent(HistoryUiEvent.ManualEntryRequested) }) {
                Text("+")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            FilterRow(state = state, onEvent = onEvent)
            HistoryList(
                pagedSessions = pagedSessions,
                state = state,
                onEvent = onEvent,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    state.dialog?.let { dialog ->
        HistoryDialogHost(dialog = dialog, subjectOptions = state.subjectOptions, onEvent = onEvent)
    }

    state.errorMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message.toDisplayText())
            onEvent(HistoryUiEvent.ErrorMessageDismissed)
        }
    }
}

@Composable
private fun HistoryTopBarActions(
    state: HistoryUiState,
    onEvent: (HistoryUiEvent) -> Unit,
) {
    if (state.isSelecting) {
        if (state.selectedIds.size >= 2) {
            TextButton(onClick = { onEvent(HistoryUiEvent.MergeRequested) }) { Text("Merge") }
        }
        TextButton(onClick = { onEvent(HistoryUiEvent.BulkDeleteRequested) }) { Text("Delete") }
        TextButton(onClick = { onEvent(HistoryUiEvent.SelectionCleared) }) { Text("Cancel") }
    }
}

@Composable
private fun FilterRow(
    state: HistoryUiState,
    onEvent: (HistoryUiEvent) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        contentPadding = PaddingValues(vertical = MaterialTheme.spacing.small),
    ) {
        item {
            FilterChip(
                selected = state.filter.subjectId == null,
                onClick = { onEvent(HistoryUiEvent.SubjectFilterChanged(null)) },
                label = { Text("All subjects") },
            )
        }
        items(state.subjectOptions, key = { it.id }) { subject ->
            FilterChip(
                selected = state.filter.subjectId == subject.id,
                onClick = { onEvent(HistoryUiEvent.SubjectFilterChanged(subject.id)) },
                label = { Text(subject.name) },
            )
        }
        if (state.filter.isNarrowed) {
            item {
                TextButton(onClick = { onEvent(HistoryUiEvent.FilterCleared) }) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun HistoryList(
    pagedSessions: androidx.paging.compose.LazyPagingItems<StudySession>,
    state: HistoryUiState,
    onEvent: (HistoryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pagedSessions.itemCount == 0) {
        EmptyState(
            message = "No study sessions here yet — start a timer or add a manual entry.",
            modifier = modifier,
        )
        return
    }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = MaterialTheme.spacing.huge)) {
        var previousDay: String? = null
        for (index in 0 until pagedSessions.itemCount) {
            val session = pagedSessions[index] ?: continue
            val day = session.startedAt.toLocalDay()
            if (day != previousDay) {
                previousDay = day
                item(key = "header-$day") {
                    DayHeader(day = day, sessions = daySessions(pagedSessions, index, day))
                }
            }
            item(key = session.id) {
                SessionRow(
                    session = session,
                    selected = session.id in state.selectedIds,
                    selecting = state.isSelecting,
                    onEvent = onEvent,
                )
            }
        }
    }
}

/** Every currently-loaded session sharing [day], scanning forward from [startIndex]. */
private fun daySessions(
    pagedSessions: androidx.paging.compose.LazyPagingItems<StudySession>,
    startIndex: Int,
    day: String,
): List<StudySession> {
    val result = mutableListOf<StudySession>()
    var index = startIndex
    while (index < pagedSessions.itemCount) {
        val session = pagedSessions[index] ?: break
        if (session.startedAt.toLocalDay() != day) break
        result += session
        index++
    }
    return result
}

@Composable
private fun DayHeader(
    day: String,
    sessions: List<StudySession>,
) {
    val totalsBySubject = sessions.groupBy { it.subjectId }.mapValues { (_, forSubject) ->
        forSubject.fold(Duration.ZERO) { acc, session -> acc + session.elapsed.counted }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.medium)) {
        Text(text = day, style = MaterialTheme.typography.titleMedium)
        totalsBySubject.forEach { (subjectId, total) ->
            Text(
                text = "${subjectId ?: "No subject"}: ${total.inWholeMinutes} min",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: StudySession,
    selected: Boolean,
    selecting: Boolean,
    onEvent: (HistoryUiEvent) -> Unit,
) {
    StudyFlowListItem(
        headline = session.subjectId ?: "No subject",
        supportingText = session.note,
        overlineText = "${session.elapsed.counted.inWholeMinutes} min",
        trailingContent = {
            Row {
                if (selecting) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onEvent(HistoryUiEvent.SelectionToggled(session.id)) },
                    )
                } else {
                    TextButton(onClick = { onEvent(HistoryUiEvent.SplitRequested(session)) }) { Text("Split") }
                    TextButton(onClick = { onEvent(HistoryUiEvent.DeleteRequested(session.id)) }) { Text("Delete") }
                }
            }
        },
        onClick = {
            if (selecting) {
                onEvent(HistoryUiEvent.SelectionToggled(session.id))
            } else {
                onEvent(HistoryUiEvent.SessionOpened(session))
            }
        },
    )
}

@Composable
private fun HistoryDialogHost(
    dialog: HistoryDialog,
    subjectOptions: List<Subject>,
    onEvent: (HistoryUiEvent) -> Unit,
) {
    when (dialog) {
        is HistoryDialog.Edit -> {
            EditSessionDialog(dialog = dialog, subjectOptions = subjectOptions, onEvent = onEvent)
        }

        is HistoryDialog.Split -> {
            SplitSessionDialog(dialog = dialog, onEvent = onEvent)
        }

        is HistoryDialog.Merge -> {
            StudyFlowDialog(
                title = "Merge ${dialog.sessionIds.size} sessions?",
                text = "Their time will be combined into one session. This can be undone afterwards.",
                onDismissRequest = { onEvent(HistoryUiEvent.DialogDismissed) },
                confirmButton = {
                    TextButton(onClick = { onEvent(HistoryUiEvent.MergeConfirmed) }) { Text("Merge") }
                },
                dismissButton = {
                    TextButton(onClick = { onEvent(HistoryUiEvent.DialogDismissed) }) { Text("Cancel") }
                },
            )
        }

        is HistoryDialog.ManualEntry -> {
            ManualEntryDialog(dialog = dialog, subjectOptions = subjectOptions, onEvent = onEvent)
        }
    }
}

@Composable
private fun EditSessionDialog(
    dialog: HistoryDialog.Edit,
    subjectOptions: List<Subject>,
    onEvent: (HistoryUiEvent) -> Unit,
) {
    StudyFlowDialog(
        title = "Edit session",
        onDismissRequest = { onEvent(HistoryUiEvent.DialogDismissed) },
        text = null,
        confirmButton = {
            TextButton(onClick = { onEvent(HistoryUiEvent.EditConfirmed) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(HistoryUiEvent.DialogDismissed) }) { Text("Cancel") }
        },
    )
    Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
        SubjectPicker(
            selectedSubjectId = dialog.subjectId,
            subjectOptions = subjectOptions,
            onSubjectSelected = { onEvent(HistoryUiEvent.EditSubjectChanged(it)) },
        )
        OutlinedTextField(
            value = dialog.note,
            onValueChange = { onEvent(HistoryUiEvent.EditNoteChanged(it)) },
            label = { Text("Note") },
        )
    }
}

@Composable
private fun SplitSessionDialog(
    dialog: HistoryDialog.Split,
    onEvent: (HistoryUiEvent) -> Unit,
) {
    StudyFlowDialog(
        title = "Split session",
        text = "The session will be divided into two at the chosen instant.",
        onDismissRequest = { onEvent(HistoryUiEvent.DialogDismissed) },
        confirmButton = {
            TextButton(onClick = { onEvent(HistoryUiEvent.SplitConfirmed) }) { Text("Split") }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(HistoryUiEvent.DialogDismissed) }) { Text("Cancel") }
        },
    )
}

@Composable
private fun ManualEntryDialog(
    dialog: HistoryDialog.ManualEntry,
    subjectOptions: List<Subject>,
    onEvent: (HistoryUiEvent) -> Unit,
) {
    StudyFlowDialog(
        title = "Add manual entry",
        onDismissRequest = { onEvent(HistoryUiEvent.DialogDismissed) },
        text = null,
        confirmButton = {
            TextButton(onClick = { onEvent(HistoryUiEvent.ManualEntryConfirmed) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(HistoryUiEvent.DialogDismissed) }) { Text("Cancel") }
        },
    )
    Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
        SubjectPicker(
            selectedSubjectId = dialog.subjectId,
            subjectOptions = subjectOptions,
            onSubjectSelected = { onEvent(HistoryUiEvent.ManualEntrySubjectChanged(it)) },
        )
        OutlinedTextField(
            value = dialog.note,
            onValueChange = { onEvent(HistoryUiEvent.ManualEntryNoteChanged(it)) },
            label = { Text("Note") },
        )
    }
}

@Composable
private fun SubjectPicker(
    selectedSubjectId: String?,
    subjectOptions: List<Subject>,
    onSubjectSelected: (String?) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        items(subjectOptions, key = { it.id }) { subject ->
            FilterChip(
                selected = selectedSubjectId == subject.id,
                onClick = { onSubjectSelected(subject.id) },
                label = { Text(subject.name) },
            )
        }
    }
}

private fun Instant.toLocalDay(): String = toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

private fun UserMessage.toDisplayText(): String =
    when (this) {
        UserMessage.Network -> "Check your connection and try again."
        UserMessage.Storage -> "Your study data could not be saved."
        UserMessage.Permission -> "StudyFlow needs permission to complete that action."
        UserMessage.Validation -> "Check the information and try again."
        UserMessage.Unknown -> "Something went wrong. Please try again."
    }
