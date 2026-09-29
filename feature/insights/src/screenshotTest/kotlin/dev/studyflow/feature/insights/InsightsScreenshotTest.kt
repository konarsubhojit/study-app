package dev.studyflow.feature.insights

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.studyflow.core.designsystem.theme.StudyFlowTheme
import dev.studyflow.core.domain.stats.AverageSessionLength
import dev.studyflow.core.domain.stats.BucketTotal
import dev.studyflow.core.domain.stats.HourOfDayTotal
import dev.studyflow.core.domain.stats.StatsRange
import dev.studyflow.core.domain.stats.SubjectTotal
import dev.studyflow.core.model.Subject
import dev.studyflow.core.testing.data.testSubject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
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

private val previewBucketTotals =
    listOf(
        BucketTotal(Instant.parse("2026-03-01T00:00:00Z"), 45.minutes, 1),
        BucketTotal(Instant.parse("2026-03-02T00:00:00Z"), 90.minutes, 2),
        BucketTotal(Instant.parse("2026-03-03T00:00:00Z"), 30.minutes, 1),
    )

private val previewHourOfDayTotals =
    (0..23).map { hour ->
        when (hour) {
            9 -> HourOfDayTotal(hour, 60.minutes, 2)
            14 -> HourOfDayTotal(hour, 30.minutes, 1)
            20 -> HourOfDayTotal(hour, 75.minutes, 1)
            else -> HourOfDayTotal(hour, Duration.ZERO, 0)
        }
    }

/**
 * The populated screen, top to bottom. Sections below the fold are only reachable because the
 * content scrolls (issue #165), so this preview is the gate on that: if the container stops being
 * scrollable the clipped layout shows up here.
 */
@Composable
private fun PopulatedInsightsPreview() {
    StudyFlowTheme(dynamicColor = false, edgeToEdge = false) {
        InsightsScreen(
            state =
                InsightsUiState(
                    range = previewRange,
                    subjectOptions = previewSubjects,
                    bucketTotals = previewBucketTotals,
                    subjectTotals =
                        listOf(
                            SubjectTotal("mathematics", 90.minutes, 2),
                            SubjectTotal("history", 45.minutes, 1),
                            SubjectTotal("biology", 30.minutes, 1),
                        ),
                    hourOfDayTotals = previewHourOfDayTotals,
                    averageSessionLength = AverageSessionLength(41.minutes, 4),
                ),
            onEvent = {},
            onOpenWeeklySummary = {},
        )
    }
}

@PreviewTest
@Preview
@Composable
private fun PopulatedInsightsDefaultFontScalePreview() {
    PopulatedInsightsPreview()
}

@PreviewTest
@Preview(fontScale = 2.0f)
@Composable
private fun PopulatedInsightsLargeFontScalePreview() {
    PopulatedInsightsPreview()
}

@PreviewTest
@Preview
@Composable
private fun EmptyInsightsPreview() {
    StudyFlowTheme(dynamicColor = false, edgeToEdge = false) {
        InsightsScreen(
            state = InsightsUiState(range = previewRange),
            onEvent = {},
            onOpenWeeklySummary = {},
        )
    }
}

/**
 * The trend chart at the data densities that used to break it (issue #168): one bucket, which
 * rendered as a full-width, full-height slab, and a year of daily buckets, which rendered as bars
 * too thin to read.
 */
@Composable
private fun TrendPreview(buckets: List<BucketTotal>) {
    StudyFlowTheme(dynamicColor = false, edgeToEdge = false) {
        InsightsScreen(
            state =
                InsightsUiState(
                    range = previewRange,
                    subjectOptions = previewSubjects,
                    bucketTotals = buckets,
                ),
            onEvent = {},
            onOpenWeeklySummary = {},
        )
    }
}

@PreviewTest
@Preview
@Composable
private fun TrendSingleBucketPreview() {
    TrendPreview(listOf(BucketTotal(Instant.parse("2026-03-01T00:00:00Z"), 45.minutes, 1)))
}

@PreviewTest
@Preview
@Composable
private fun TrendManyBucketsPreview() {
    TrendPreview(
        List(90) { day ->
            BucketTotal(
                bucketStart = Instant.parse("2026-01-01T00:00:00Z") + day.days,
                totalCounted = ((day * 7) % 90).minutes,
                sessionCount = 1,
            )
        },
    )
}
