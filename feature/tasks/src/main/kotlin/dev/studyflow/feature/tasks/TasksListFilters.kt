package dev.studyflow.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.TaskPriority

@Composable
internal fun FilterBar(
    state: TasksListUiState,
    onEvent: (TasksListUiEvent) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium),
    ) {
        FilterSearchField(
            query = state.filter.query,
            onQueryChange = { onEvent(TasksListUiEvent.QueryChanged(it)) },
        )
        FilterChipsRow(state = state, onEvent = onEvent)
    }
}

@Composable
private fun FilterSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(text = "Search tasks") },
        singleLine = true,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Search tasks by title, notes or tag" },
    )
}

@Composable
private fun FilterChipsRow(
    state: TasksListUiState,
    onEvent: (TasksListUiEvent) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.small),
    ) {
        item(key = "task-sort") {
            TaskSortChip(
                sort = state.filter.sort,
                onEvent = onEvent,
                modifier = Modifier.animateItem(),
            )
        }
        items(TaskPriority.entries.toList(), key = { "priority-${it.name}" }) { priority ->
            FilterChip(
                selected = state.filter.priority == priority,
                onClick = {
                    onEvent(
                        TasksListUiEvent.PriorityFilterChanged(
                            if (state.filter.priority == priority) null else priority,
                        ),
                    )
                },
                label = { Text(text = priority.name.lowercase().replaceFirstChar(Char::uppercase)) },
                modifier = Modifier.animateItem(),
            )
        }
        items(state.filterOptions.subjectIds.toList(), key = { "subject-$it" }) { subjectId ->
            FilterChip(
                selected = state.filter.subjectId == subjectId,
                onClick = {
                    onEvent(
                        TasksListUiEvent.SubjectFilterChanged(
                            if (state.filter.subjectId == subjectId) null else subjectId,
                        ),
                    )
                },
                label = { Text(text = subjectId) },
                modifier = Modifier.animateItem(),
            )
        }
        items(state.filterOptions.tags.toList(), key = { "tag-$it" }) { tag ->
            FilterChip(
                selected = state.filter.tag == tag,
                onClick = {
                    onEvent(TasksListUiEvent.TagFilterChanged(if (state.filter.tag == tag) null else tag))
                },
                label = { Text(text = "#$tag") },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun TaskSortChip(
    sort: TaskSort,
    onEvent: (TasksListUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sortedByPriority = sort == TaskSort.PRIORITY
    FilterChip(
        selected = sortedByPriority,
        onClick = {
            val nextSort = if (sortedByPriority) TaskSort.DUE_DATE else TaskSort.PRIORITY
            onEvent(TasksListUiEvent.SortChanged(nextSort))
        },
        label = { Text(text = if (sortedByPriority) "Sorted by priority" else "Sorted by due date") },
        modifier = modifier,
    )
}
