package dev.studyflow.feature.insights

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.studyflow.core.designsystem.theme.StudyFlowTheme
import dev.studyflow.core.domain.stats.SubjectTotal
import dev.studyflow.core.domain.stats.StatsRange
import dev.studyflow.core.testing.data.testSubject
import dev.studyflow.core.model.Subject
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val previewRange = StatsRange(Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-03-31T00:00:00Z"))

private val previewSubjects =
    listOf(
        testSubject(id = "mathematics", name = "Mathematics"),
        testSubject(id = "history", name = "History"),
        testSubject(id = "biology", name = "Biology"),
    )

@Composable
private fun InsightsPreview(subjectTotals: List<SubjectTotal>) {
    StudyFlowTheme(dynamicColor = false, edgeToEdge = false) {
        InsightsScreen(
            state =
                InsightsUiState(
                    range = previewRange,
                    subjectOptions = previewSubjects,
                    subjectTotals = subjectTotals,
                ),
            onEvent = {},
            onOpenWeeklySummary = {},
        )
    }
}

@PreviewTest
@Preview
@Composable
private fun SubjectBreakdownSingleSubjectPreview() {
    InsightsPreview(listOf(SubjectTotal("mathematics", 60.minutes, 1)))
}

@PreviewTest
@Preview
@Composable
private fun SubjectBreakdownMaximumSubjectPreview() {
    InsightsPreview(
        listOf(
            SubjectTotal("mathematics", 60.minutes, 2),
            SubjectTotal("history", 30.minutes, 1),
            SubjectTotal("biology", 15.minutes, 1),
        ),
    )
}

@PreviewTest
@Preview
@Composable
private fun SubjectBreakdownZeroTotalsPreview() {
    InsightsPreview(
        listOf(
            SubjectTotal("mathematics", 0.minutes, 0),
            SubjectTotal("history", 0.minutes, 0),
        ),
    )
}

@PreviewTest
@Preview
@Composable
private fun SubjectBreakdownEqualTotalsPreview() {
    InsightsPreview(
        listOf(
            SubjectTotal("mathematics", 30.minutes, 1),
            SubjectTotal("history", 30.minutes, 1),
        ),
    )
}
