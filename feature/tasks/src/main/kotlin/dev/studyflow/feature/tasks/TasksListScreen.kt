package dev.studyflow.feature.tasks

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.motion.StudyFlowMotion
import dev.studyflow.core.designsystem.motion.StudyFlowSharedElementScope
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.ui.components.StudyFlowTopAppBar
import dev.studyflow.core.ui.state.EmptyState
import dev.studyflow.core.ui.state.LoadingState

/**
 * The task list: quick-add, grouped sections, filters and swipe actions (issue #46).
 */
@Composable
public fun TasksListRoute(
    modifier: Modifier = Modifier,
    onTaskSelect: (String) -> Unit = {},
    sharedElementScope: StudyFlowSharedElementScope? = null,
    viewModel: TasksListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnTaskSelect by rememberUpdatedState(onTaskSelect)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TasksListUiEffect.NavigateToTaskDetail -> currentOnTaskSelect(effect.taskId)
            }
        }
    }

    TasksListScreen(
        state = state,
        onEvent = viewModel::onEvent,
        modifier = modifier,
        sharedElementScope = sharedElementScope,
    )
}

@Composable
public fun TasksListScreen(
    state: TasksListUiState,
    onEvent: (TasksListUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    sharedElementScope: StudyFlowSharedElementScope? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            StudyFlowTopAppBar(title = "Tasks")
            QuickAddBar(
                title = state.quickAddTitle,
                dueDate = state.quickAddDueDate,
                onTitleChange = { onEvent(TasksListUiEvent.QuickAddTitleChanged(it)) },
                onDueDateChange = { onEvent(TasksListUiEvent.QuickAddDueDateChanged(it)) },
                onSubmit = { onEvent(TasksListUiEvent.QuickAddSubmitted) },
            )
            FilterBar(state = state, onEvent = onEvent)
            TaskListContent(state = state, onEvent = onEvent, sharedElementScope = sharedElementScope)
        }

        state.undo?.let { undo ->
            TasksListUndoSnackbar(undo = undo, onEvent = onEvent)
        }
    }
}

@Composable
private fun TaskListContent(
    state: TasksListUiState,
    onEvent: (TasksListUiEvent) -> Unit,
    sharedElementScope: StudyFlowSharedElementScope?,
) {
    AnimatedContent(
        targetState = state.displayState,
        transitionSpec = {
            fadeIn(StudyFlowMotion.effects()) togetherWith fadeOut(StudyFlowMotion.effects())
        },
        label = "task list state",
    ) { displayState ->
        when (displayState) {
            ListDisplayState.Loading -> {
                LoadingState(modifier = Modifier.fillMaxSize())
            }

            ListDisplayState.Empty -> {
                EmptyState(
                    message =
                        "No tasks yet. Type a title above and tap Add to create your first one — " +
                            "add Today, Tomorrow or Next week to see it grouped automatically.",
                    modifier = Modifier.fillMaxSize(),
                )
            }

            ListDisplayState.Content -> {
                TaskSectionsList(
                    state = state,
                    onEvent = onEvent,
                    sharedElementScope = sharedElementScope,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.TasksListUndoSnackbar(
    undo: TaskListUndo,
    onEvent: (TasksListUiEvent) -> Unit,
) {
    Snackbar(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .padding(MaterialTheme.spacing.medium),
        action = {
            TextButton(
                onClick = { onEvent(TasksListUiEvent.UndoRequested) },
                modifier = Modifier.semantics { contentDescription = "Undo" },
            ) {
                Text(text = "Undo")
            }
        },
        dismissAction = {
            TextButton(onClick = { onEvent(TasksListUiEvent.UndoDismissed) }) {
                Text(text = "Dismiss")
            }
        },
    ) {
        Text(text = undo.message())
    }
}

private fun TaskListUndo.message(): String =
    when (this) {
        is TaskListUndo.Completed -> "Marked \"${original.title}\" done"
        is TaskListUndo.Snoozed -> "Snoozed \"${original.title}\""
    }

private enum class ListDisplayState { Loading, Empty, Content }

private val TasksListUiState.displayState: ListDisplayState
    get() =
        when {
            loading -> ListDisplayState.Loading
            hasNoTasksWhatsoever -> ListDisplayState.Empty
            else -> ListDisplayState.Content
        }
