package dev.studyflow.feature.insights

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.domain.stats.AverageSessionLength
import dev.studyflow.core.domain.stats.BucketTotal
import dev.studyflow.core.domain.stats.HourOfDayTotal
import dev.studyflow.core.domain.stats.StatsBucketSize
import dev.studyflow.core.domain.stats.SubjectTotal
import dev.studyflow.core.model.Subject
import dev.studyflow.core.ui.components.StudyFlowTopAppBar
import dev.studyflow.core.ui.state.EmptyState
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The insights screen: SQL-driven trend, subject breakdown and time-of-day charts over a
 * user-chosen range, with a CSV export (issue #60).
 *
 * Every chart pairs a Canvas-free, plain-Compose visual (a row of [Box]es sized by fraction, never
 * an actual `Canvas`, so nothing here needs to reimplement text layout at 200% font scale) with an
 * always-visible, non-decorative list of the same numbers: the visual is `clearAndSetSemantics`'d
 * with one summary description and the list underneath is what a screen reader and a 200%-scaled
 * layout actually rely on.
 */
@Composable
public fun InsightsRoute(
    onOpenWeeklySummary: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is InsightsUiEffect.ShareCsv -> {
                    val send =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_TEXT, effect.csv)
                        }
                    context.startActivity(Intent.createChooser(send, "Export study time"))
                }
            }
        }
    }

    InsightsScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onOpenWeeklySummary = onOpenWeeklySummary,
        modifier = modifier,
    )
}

@Composable
public fun InsightsScreen(
    state: InsightsUiState,
    onEvent: (InsightsUiEvent) -> Unit,
    onOpenWeeklySummary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        StudyFlowTopAppBar(
            title = "Insights",
            actions = {
                TextButton(onClick = onOpenWeeklySummary) {
                    Text("This week")
                }
                Button(onClick = { onEvent(InsightsUiEvent.ExportRequested) }) {
                    Text("Export CSV")
                }
            },
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
        ) {
            Spacer(Modifier.height(MaterialTheme.spacing.small))
            RangePresetRow(state.rangePreset, onEvent)
            BucketSizeRow(state.bucketSize, onEvent)
            SubjectFilterRow(state.subjectId, state.subjectOptions, onEvent)
            TrendSection(state.bucketTotals, state.bucketSize)
            HorizontalDivider()
            SubjectBreakdownSection(state.subjectTotals, state.subjectOptions)
            HorizontalDivider()
            HourOfDaySection(state.hourOfDayTotals)
            HorizontalDivider()
            AverageSessionLengthSection(state.averageSessionLength)
            Spacer(Modifier.height(MaterialTheme.spacing.huge))
        }
    }
}

@Composable
private fun RangePresetRow(
    selected: RangePreset,
    onEvent: (InsightsUiEvent) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        items(RangePreset.entries, key = { it.name }) { preset ->
            FilterChip(
                selected = preset == selected,
                onClick = { onEvent(InsightsUiEvent.RangePresetSelected(preset)) },
                label = { Text(preset.label()) },
            )
        }
    }
}

@Composable
private fun BucketSizeRow(
    selected: StatsBucketSize,
    onEvent: (InsightsUiEvent) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        items(StatsBucketSize.entries, key = { it.name }) { bucketSize ->
            FilterChip(
                selected = bucketSize == selected,
                onClick = { onEvent(InsightsUiEvent.BucketSizeChanged(bucketSize)) },
                label = { Text(bucketSize.label()) },
            )
        }
    }
}

@Composable
private fun SubjectFilterRow(
    selectedSubjectId: String?,
    subjects: List<Subject>,
    onEvent: (InsightsUiEvent) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        item {
            FilterChip(
                selected = selectedSubjectId == null,
                onClick = { onEvent(InsightsUiEvent.SubjectFilterChanged(null)) },
                label = { Text("All subjects") },
            )
        }
        items(subjects, key = { it.id }) { subject ->
            FilterChip(
                selected = selectedSubjectId == subject.id,
                onClick = { onEvent(InsightsUiEvent.SubjectFilterChanged(subject.id)) },
                label = { Text(subject.name) },
            )
        }
    }
}

@Composable
private fun TrendSection(
    buckets: List<BucketTotal>,
    bucketSize: StatsBucketSize,
) {
    Column {
        SectionHeading("Studied time (${bucketSize.label().lowercase()})")
        if (buckets.isEmpty()) {
            EmptyState(message = "No sessions in this range yet.")
            return@Column
        }
        val maxMinutes = buckets.maxOf { it.totalCounted.inWholeMinutes }.coerceAtLeast(1)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(CHART_HEIGHT)
                    .clearAndSetSemantics {
                        contentDescription =
                            "Trend chart: " +
                            buckets.joinToString {
                                "${it.bucketStart.toDateLabel()}, ${it.totalCounted.minutesLabel()}"
                            }
                    },
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            verticalAlignment = Alignment.Bottom,
        ) {
            buckets.forEach { bucket ->
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(bucket.totalCounted.inWholeMinutes.toFloat() / maxMinutes)
                            .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Column(modifier = Modifier.padding(top = MaterialTheme.spacing.small)) {
            buckets.forEach { bucket ->
                Text(
                    text =
                        "${bucket.bucketStart.toDateLabel()}: ${bucket.totalCounted.minutesLabel()} " +
                            "(${bucket.sessionCount} session${if (bucket.sessionCount == 1) "" else "s"})",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SubjectBreakdownSection(
    subjectTotals: List<SubjectTotal>,
    subjectOptions: List<Subject>,
) {
    Column {
        SectionHeading("By subject")
        if (subjectTotals.isEmpty()) {
            EmptyState(message = "No sessions in this range yet.")
            return@Column
        }
        val maxMinutes = subjectTotals.maxOf { it.totalCounted.inWholeMinutes }.coerceAtLeast(1)
        Column(
            modifier =
                Modifier.clearAndSetSemantics {
                    contentDescription =
                        "By-subject chart: " +
                        subjectTotals.joinToString {
                            "${it.subjectId.subjectName(subjectOptions)}, ${it.totalCounted.minutesLabel()}"
                        }
                },
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            subjectTotals.forEach { total ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .weight(
                                    total.totalCounted.inWholeMinutes
                                        .toFloat()
                                        .coerceAtLeast(MIN_BAR_WEIGHT),
                                ).height(BAR_HEIGHT)
                                .background(MaterialTheme.colorScheme.secondary),
                    )
                    Box(modifier = Modifier.weight((maxMinutes - total.totalCounted.inWholeMinutes).toFloat()))
                }
            }
        }
        Column(modifier = Modifier.padding(top = MaterialTheme.spacing.small)) {
            subjectTotals.forEach { total ->
                Text(
                    text =
                        "${total.subjectId.subjectName(subjectOptions)}: ${total.totalCounted.minutesLabel()} " +
                            "(${total.sessionCount} session${if (total.sessionCount == 1) "" else "s"})",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun HourOfDaySection(hourOfDayTotals: List<HourOfDayTotal>) {
    Column {
        SectionHeading("Time of day")
        if (hourOfDayTotals.all { it.sessionCount == 0 }) {
            EmptyState(message = "No sessions in this range yet.")
            return@Column
        }
        val maxMinutes = hourOfDayTotals.maxOf { it.totalCounted.inWholeMinutes }.coerceAtLeast(1)
        LazyRow(
            modifier =
                Modifier.clearAndSetSemantics {
                    contentDescription =
                        "Time-of-day heatmap: " +
                        hourOfDayTotals.joinToString { "${it.hourOfDay}:00, ${it.totalCounted.minutesLabel()}" }
                },
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            items(hourOfDayTotals, key = { it.hourOfDay }) { bucket ->
                val intensity = (bucket.totalCounted.inWholeMinutes.toFloat() / maxMinutes).coerceIn(0f, 1f)
                Box(
                    modifier =
                        Modifier
                            .size(HEATMAP_CELL)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = intensity.coerceAtLeast(MIN_ALPHA)),
                            ),
                )
            }
        }
        Column(modifier = Modifier.padding(top = MaterialTheme.spacing.small)) {
            hourOfDayTotals.filter { it.sessionCount > 0 }.forEach { bucket ->
                Text(
                    text =
                        "${bucket.hourOfDay}:00: ${bucket.totalCounted.minutesLabel()} " +
                            "(${bucket.sessionCount} session${if (bucket.sessionCount == 1) "" else "s"})",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AverageSessionLengthSection(average: AverageSessionLength) {
    Column {
        SectionHeading("Average session length")
        Text(
            text =
                if (average.sessionCount == 0) {
                    "No sessions in this range yet."
                } else {
                    "${average.average.minutesLabel()} across ${average.sessionCount} sessions"
                },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

private fun RangePreset.label(): String =
    when (this) {
        RangePreset.LAST_7_DAYS -> "7 days"
        RangePreset.LAST_30_DAYS -> "30 days"
        RangePreset.LAST_90_DAYS -> "90 days"
        RangePreset.LAST_365_DAYS -> "1 year"
    }

private fun StatsBucketSize.label(): String =
    when (this) {
        StatsBucketSize.DAY -> "Daily"
        StatsBucketSize.WEEK -> "Weekly"
        StatsBucketSize.MONTH -> "Monthly"
    }

private fun String?.subjectName(subjects: List<Subject>): String =
    this?.let { id -> subjects.firstOrNull { it.id == id }?.name } ?: "No subject"

private fun Duration.minutesLabel(): String = "$inWholeMinutes min"

private fun Instant.toDateLabel(): String = toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

private val CHART_HEIGHT = 160.dp
private val BAR_HEIGHT = 24.dp
private val HEATMAP_CELL = 20.dp
private const val MIN_BAR_WEIGHT = 0.01f
private const val MIN_ALPHA = 0.08f
