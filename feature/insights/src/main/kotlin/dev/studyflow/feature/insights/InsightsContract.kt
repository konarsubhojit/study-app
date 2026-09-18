package dev.studyflow.feature.insights

import dev.studyflow.core.domain.stats.AverageSessionLength
import dev.studyflow.core.domain.stats.BucketTotal
import dev.studyflow.core.domain.stats.HourOfDayTotal
import dev.studyflow.core.domain.stats.StatsBucketSize
import dev.studyflow.core.domain.stats.StatsRange
import dev.studyflow.core.domain.stats.SubjectTotal
import dev.studyflow.core.model.Subject
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** How far back the insights screen looks, relative to "now" (issue #60). */
public enum class RangePreset(
    internal val length: Duration,
) {
    LAST_7_DAYS(7.days),
    LAST_30_DAYS(30.days),
    LAST_90_DAYS(90.days),
    LAST_365_DAYS(365.days),
    ;

    /** The half-open range this preset covers, ending at [now]. */
    public fun rangeEndingAt(now: Instant): StatsRange = StatsRange(from = now - length, to = now)
}

/**
 * Everything the insights screen renders.
 *
 * The chart data ([bucketTotals], [subjectTotals], [hourOfDayTotals], [averageSessionLength]) is
 * kept flat here rather than split into per-chart sub-states: every one of them reacts to the same
 * three filters ([range], [bucketSize], [subjectId]), so a screen never needs to reconcile four
 * charts showing four different ranges.
 */
public data class InsightsUiState(
    val rangePreset: RangePreset = RangePreset.LAST_30_DAYS,
    val range: StatsRange,
    val bucketSize: StatsBucketSize = StatsBucketSize.DAY,
    val subjectId: String? = null,
    val subjectOptions: List<Subject> = emptyList(),
    val bucketTotals: List<BucketTotal> = emptyList(),
    val subjectTotals: List<SubjectTotal> = emptyList(),
    val hourOfDayTotals: List<HourOfDayTotal> = emptyList(),
    val averageSessionLength: AverageSessionLength = AverageSessionLength(Duration.ZERO, 0),
    val isExporting: Boolean = false,
) : UiState {
    public companion object {
        /** The state before any data has loaded — [now] anchors the default 30-day range. */
        public fun initial(now: Instant): InsightsUiState =
            InsightsUiState(range = RangePreset.LAST_30_DAYS.rangeEndingAt(now))
    }
}

public sealed interface InsightsUiEvent : UiEvent {
    public data class RangePresetSelected(
        val preset: RangePreset,
    ) : InsightsUiEvent

    public data class CustomRangeSelected(
        val from: Instant,
        val to: Instant,
    ) : InsightsUiEvent

    public data class BucketSizeChanged(
        val bucketSize: StatsBucketSize,
    ) : InsightsUiEvent

    public data class SubjectFilterChanged(
        val subjectId: String?,
    ) : InsightsUiEvent

    public data object ExportRequested : InsightsUiEvent
}

public sealed interface InsightsUiEffect : UiEffect {
    /** The filtered range's data, as CSV text ready to hand to a share intent. */
    public data class ShareCsv(
        val csv: String,
    ) : InsightsUiEffect
}
