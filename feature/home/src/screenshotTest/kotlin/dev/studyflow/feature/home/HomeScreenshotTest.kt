package dev.studyflow.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.studyflow.core.designsystem.theme.StudyFlowTheme

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
    HomePreview(HomeUiState(focusTime = kotlin.time.Duration.parse("35m"), streakDays = 4))
}

@PreviewTest
@Preview
@Composable
private fun HomePowerUserPreview() {
    HomePreview(HomeUiState(focusTime = kotlin.time.Duration.parse("2h 15m"), streakDays = 21))
}
