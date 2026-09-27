package dev.studyflow.feature.insights

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * Every chart pairs a plain-Compose visual (boxes sized by fraction, and for a dense range a
 * `Canvas` that draws an area and carries no text of its own) with an always-visible,
 * non-decorative list of the same numbers: the visual is `clearAndSetSemantics`'d with one summary
 * description and the list underneath is what a screen reader and a 200%-scaled layout actually
 * rely on. No chart draws a label itself, so none of them has to reimplement text layout.
 */
@Composable
public fun InsightsRoute(
    onOpenWeeklySummary: () -> Unit,
    onOpenHistory: () -> Unit,
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
        onOpenHistory = onOpenHistory,
        modifier = modifier,
    )
}

@Composable
public fun InsightsScreen(
    state: InsightsUiState,
    onEvent: (InsightsUiEvent) -> Unit,
    onOpenWeeklySummary: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenHistory: () -> Unit = {},
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = MaterialTheme.spacing.medium,
                    end = MaterialTheme.spacing.medium,
                    top = MaterialTheme.spacing.small,
                    // The bottom bar inset is already applied by the app scaffold; this only keeps
                    // the last section clear of it rather than flush against it.
                    bottom = MaterialTheme.spacing.huge,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
        ) {
            item(key = "range-preset") {
                RangePresetRow(state.rangePreset, onEvent, Modifier.animateItem())
            }
            item(key = "bucket-size") {
                BucketSizeRow(state.bucketSize, onEvent, Modifier.animateItem())
            }
            item(key = "subject-filter") {
                SubjectFilterRow(state.subjectId, state.subjectOptions, onEvent, Modifier.animateItem())
            }
            item(key = "trend") {
                TrendSection(state.bucketTotals, state.bucketSize, Modifier.animateItem())
            }
            item(key = "trend-divider") { HorizontalDivider(Modifier.animateItem()) }
            item(key = "subject-breakdown") {
                SubjectBreakdownSection(state.subjectTotals, state.subjectOptions, Modifier.animateItem())
            }
            item(key = "subject-divider") { HorizontalDivider(Modifier.animateItem()) }
            item(key = "hour-of-day") {
                HourOfDaySection(state.hourOfDayTotals, Modifier.animateItem())
            }
            item(key = "hour-divider") { HorizontalDivider(Modifier.animateItem()) }
            item(key = "average-session-length") {
                AverageSessionLengthSection(state.averageSessionLength, Modifier.animateItem())
            }
            // History reads the same sessions these statistics are computed from, so it is reached
            // from here rather than from a bottom-bar destination of its own (issue #167).
            item(key = "history") {
                TextButton(onClick = onOpenHistory, modifier = Modifier.animateItem()) {
                    Text("Browse session history")
                }
            }
        }
    }
}

@Composable
private fun RangePresetRow(
    selected: RangePreset,
    onEvent: (InsightsUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        items(RangePreset.entries, key = { it.name }) { preset ->
            FilterChip(
                selected = preset == selected,
                onClick = { onEvent(InsightsUiEvent.RangePresetSelected(preset)) },
                label = { Text(preset.label()) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun BucketSizeRow(
    selected: StatsBucketSize,
    onEvent: (InsightsUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        items(StatsBucketSize.entries, key = { it.name }) { bucketSize ->
            FilterChip(
                selected = bucketSize == selected,
                onClick = { onEvent(InsightsUiEvent.BucketSizeChanged(bucketSize)) },
                label = { Text(bucketSize.label()) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun SubjectFilterRow(
    selectedSubjectId: String?,
    subjects: List<Subject>,
    onEvent: (InsightsUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
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
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun TrendSection(
    buckets: List<BucketTotal>,
    bucketSize: StatsBucketSize,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeading("Studied time (${bucketSize.label().lowercase()})")
        if (buckets.isEmpty()) {
            EmptyState(message = "No sessions in this range yet.")
            return@Column
        }
        val minutes = buckets.map { it.totalCounted.inWholeMinutes }
        val peak = minutes.max()
        Text(
            text = "Peak ${peak.minutesLabel()}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = MaterialTheme.spacing.extraSmall),
        )
        val plotModifier =
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .clearAndSetSemantics {
                    contentDescription =
                        "Trend chart: " +
                        buckets.joinToString {
                            "${it.bucketStart.toDateLabel()}, ${it.totalCounted.minutesLabel()}"
                        }
                }
        if (ChartGeometry.usesAreaChart(buckets.size)) {
            TrendArea(fractions = ChartGeometry.fractions(minutes), modifier = plotModifier)
        } else {
            TrendBars(fractions = ChartGeometry.fractions(minutes), modifier = plotModifier)
        }
        // The baseline is what makes a short bar read as a small value rather than as a bar that
        // failed to draw.
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.extraSmall),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = buckets.first().bucketStart.toDateLabel(), style = MaterialTheme.typography.labelSmall)
            if (buckets.size > 1) {
                Text(text = buckets.last().bucketStart.toDateLabel(), style = MaterialTheme.typography.labelSmall)
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

/**
 * The trend as bars, one per bucket.
 *
 * Each bar is capped at [BAR_MAX_WIDTH] and centred in its slot, so a range with a single bucket
 * renders a bar rather than a screen-wide slab.
 */
@Composable
private fun TrendBars(
    fractions: List<Float>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        verticalAlignment = Alignment.Bottom,
    ) {
        fractions.forEach { fraction ->
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier =
                        Modifier
                            .widthIn(max = BAR_MAX_WIDTH)
                            .fillMaxWidth()
                            .fillMaxHeight(fraction)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/**
 * The trend as a filled area, for ranges with more buckets than a bar chart can show legibly.
 *
 * This is the one drawing in the screen that is not plain layout, and it deliberately contains no
 * text: the value list underneath stays the readable copy of the same numbers, so nothing here has
 * to survive a 200% font scale.
 */
@Composable
private fun TrendArea(
    fractions: List<Float>,
    modifier: Modifier = Modifier,
) {
    val plotColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        if (fractions.isEmpty()) return@Canvas
        val stepX = if (fractions.size > 1) size.width / (fractions.size - 1) else size.width
        val points = fractions.mapIndexed { index, fraction -> Offset(index * stepX, size.height * (1f - fraction)) }
        val area =
            Path().apply {
                moveTo(points.first().x, size.height)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, size.height)
                close()
            }
        drawPath(path = area, color = plotColor, alpha = AREA_ALPHA)
        val line =
            Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
        drawPath(path = line, color = plotColor, style = Stroke(width = AREA_LINE_WIDTH.toPx()))
    }
}

@Composable
private fun SubjectBreakdownSection(
    subjectTotals: List<SubjectTotal>,
    subjectOptions: List<Subject>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
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
                val fraction =
                    ChartGeometry.fractionOf(
                        value = total.totalCounted.inWholeMinutes,
                        max = maxMinutes,
                    )
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(BAR_HEIGHT)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.secondary),
                )
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
private fun HourOfDaySection(
    hourOfDayTotals: List<HourOfDayTotal>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
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
                            .animateItem()
                            .size(HEATMAP_CELL)
                            .clip(MaterialTheme.shapes.extraSmall)
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
private fun AverageSessionLengthSection(
    average: AverageSessionLength,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
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

private fun Duration.minutesLabel(): String = inWholeMinutes.minutesLabel()

private fun Long.minutesLabel(): String = "$this min"

private fun Instant.toDateLabel(): String = toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

private val CHART_HEIGHT = 160.dp
private val BAR_HEIGHT = 24.dp
private val BAR_MAX_WIDTH = 40.dp
private val AREA_LINE_WIDTH = 2.dp
private val HEATMAP_CELL = 20.dp
private const val AREA_ALPHA = 0.3f
private const val MIN_ALPHA = 0.08f
