package dev.studyflow.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.studyflow.core.designsystem.theme.StudyFlowTheme
import dev.studyflow.core.testing.data.testMaterial
import dev.studyflow.core.testing.data.testStudySession
import dev.studyflow.core.testing.data.testStudyTask
import kotlinx.datetime.LocalDateTime

@Composable
private fun HomePreview(state: HomeUiState) {
    StudyFlowTheme(dynamicColor = false, edgeToEdge = false) {
        HomeScreen(state = state, actions = HomeActions())
    }
}

@PreviewTest
@Preview
@Composable
private fun HomeEmptyPreview() {
    HomePreview(HomeUiState())
}

@PreviewTest
@Preview
@Composable
private fun HomeTypicalPreview() {
    HomePreview(
        HomeUiState(
            focusTime = kotlin.time.Duration.parse("35m"),
            streakDays = 4,
            nextTask = testStudyTask(title = "Finish biology notes", dueAt = LocalDateTime(2026, 3, 1, 17, 0)),
            recentMaterials = listOf(testMaterial(displayName = "Biology lecture slides.pdf")),
        ),
    )
}

@PreviewTest
@Preview
@Composable
private fun HomePowerUserPreview() {
    HomePreview(
        HomeUiState(
            focusTime = kotlin.time.Duration.parse("2h 15m"),
            streakDays = 21,
            activeSession = testStudySession(note = "Practice exam questions"),
            nextTask = testStudyTask(title = "Review flashcards", dueAt = LocalDateTime(2026, 3, 1, 12, 0)),
            recentMaterials =
                listOf(
                    testMaterial(id = "material-1", displayName = "Calculus workbook.pdf"),
                    testMaterial(id = "material-2", displayName = "Chemistry formula sheet.pdf"),
                    testMaterial(id = "material-3", displayName = "History essay plan.pdf"),
                ),
        ),
    )
}
