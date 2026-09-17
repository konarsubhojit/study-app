package dev.studyflow.feature.tasks

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.studyflow.core.designsystem.theme.StudyFlowTheme
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.RecurrenceRule
import dev.studyflow.core.model.ReminderTrigger
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subtask
import dev.studyflow.core.model.TaskPriority
import dev.studyflow.core.testing.data.testReminder
import dev.studyflow.core.testing.data.testStudyTask
import kotlinx.datetime.LocalDateTime

// Fixtures, not the shared `testStudyTask` defaults directly: a screenshot is a specification of
// what one section actually looks like, so each preview below names the handful of fields that
// make it that section rather than relying on unrelated defaults staying the same.

private val overdueTask =
    testStudyTask(id = "overdue-1", title = "Submit lab report", dueAt = LocalDateTime(2026, 2, 20, 9, 0))

private val todayTask =
    testStudyTask(
        id = "today-1",
        title = "Revise integration by parts",
        dueAt = LocalDateTime(2026, 3, 1, 17, 0),
        priority = TaskPriority.HIGH,
    )

private val upcomingTask =
    testStudyTask(id = "upcoming-1", title = "Read chapter 9", dueAt = LocalDateTime(2026, 3, 10, 9, 0))

private val somedayTask =
    testStudyTask(id = "someday-1", title = "Reorganise flashcards", dueAt = null)

@Composable
private fun TasksListPreviewScreen(state: TasksListUiState) {
    StudyFlowTheme(dynamicColor = false, edgeToEdge = false) {
        TasksListScreen(state = state, onEvent = {})
    }
}

@PreviewTest
@Preview
@Composable
private fun TasksListOverdueSectionPreview() {
    TasksListPreviewScreen(TasksListUiState(loading = false, overdue = listOf(overdueTask)))
}

@PreviewTest
@Preview
@Composable
private fun TasksListTodaySectionPreview() {
    TasksListPreviewScreen(TasksListUiState(loading = false, today = listOf(todayTask)))
}

@PreviewTest
@Preview
@Composable
private fun TasksListUpcomingSectionPreview() {
    TasksListPreviewScreen(TasksListUiState(loading = false, upcoming = listOf(upcomingTask)))
}

@PreviewTest
@Preview
@Composable
private fun TasksListSomedaySectionPreview() {
    TasksListPreviewScreen(TasksListUiState(loading = false, someday = listOf(somedayTask)))
}

@PreviewTest
@Preview
@Composable
private fun TasksListAllSectionsPreview() {
    TasksListPreviewScreen(
        TasksListUiState(
            loading = false,
            overdue = listOf(overdueTask),
            today = listOf(todayTask),
            upcoming = listOf(upcomingTask),
            someday = listOf(somedayTask),
        ),
    )
}

@PreviewTest
@Preview
@Composable
private fun TasksListEmptyStatePreview() {
    TasksListPreviewScreen(TasksListUiState(loading = false))
}

private val detailTask: StudyTask =
    testStudyTask(
        id = "task-detail-1",
        title = "Write essay outline",
        notes = "Cover the counter-argument in the third paragraph.",
        subjectId = "subject-english",
        dueAt = LocalDateTime(2026, 3, 5, 17, 0),
        priority = TaskPriority.HIGH,
        subtasks =
            listOf(
                Subtask(id = "subtask-1", title = "Draft thesis statement", completedAt = null),
                Subtask(id = "subtask-2", title = "List supporting quotes", completedAt = null),
            ),
        reminders = listOf(testReminder(id = "reminder-1", trigger = ReminderTrigger.BeforeDue())),
        recurrence = RecurrenceRule(frequency = RecurrenceFrequency.WEEKLY, interval = 1),
    )

@PreviewTest
@Preview
@Composable
private fun TaskDetailScreenPreview() {
    StudyFlowTheme(dynamicColor = false, edgeToEdge = false) {
        TaskDetailScreen(state = TaskDetailUiState(loading = false, task = detailTask), onEvent = {})
    }
}

@PreviewTest
@Preview
@Composable
private fun TaskDetailNotFoundPreview() {
    StudyFlowTheme(dynamicColor = false, edgeToEdge = false) {
        TaskDetailScreen(state = TaskDetailUiState(loading = false, task = null), onEvent = {})
    }
}
