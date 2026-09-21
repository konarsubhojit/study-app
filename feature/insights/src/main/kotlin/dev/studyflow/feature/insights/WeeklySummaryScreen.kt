package dev.studyflow.feature.insights

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.domain.stats.WeeklySummary
import dev.studyflow.core.domain.stats.WeeklySummaryCopy
import dev.studyflow.core.ui.components.StudyFlowTopAppBar
import dev.studyflow.core.ui.state.LoadingState

/**
 * The full weekly recap the digest notification opens (issue #63).
 *
 * Every line is rendered through [WeeklySummaryCopy], the same builder the notification and the
 * shareable card use, so the three never phrase the same week differently. There is no chart here
 * on purpose: the recap answers "how did last week go?", and the insights screen is one tap away
 * for the question after that.
 */
@Composable
public fun WeeklySummaryRoute(
    modifier: Modifier = Modifier,
    viewModel: WeeklySummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is WeeklySummaryUiEffect.ShareSummary -> {
                    val send =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, effect.text)
                        }
                    context.startActivity(Intent.createChooser(send, "Share your week"))
                }
            }
        }
    }

    WeeklySummaryScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

@Composable
public fun WeeklySummaryScreen(
    state: WeeklySummaryUiState,
    onEvent: (WeeklySummaryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        StudyFlowTopAppBar(
            title = "Weekly summary",
            actions = {
                Button(
                    onClick = { onEvent(WeeklySummaryUiEvent.ShareRequested) },
                    enabled = state.summary != null,
                ) {
                    Text("Share")
                }
            },
        )
        val summary = state.summary
        if (summary == null) {
            LoadingState(modifier = Modifier.fillMaxSize())
            return@Column
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            SummaryHeadline(summary)
            HorizontalDivider()
            SummaryRow(label = "Top subjects", value = WeeklySummaryCopy.topSubjectsLabel(summary, state::subjectName))
            SummaryRow(label = "Streak", value = WeeklySummaryCopy.streakLabel(summary))
            SummaryRow(label = "Goal", value = WeeklySummaryCopy.goalLabel(summary))
            SummaryRow(label = "Compared with last week", value = WeeklySummaryCopy.deltaLabel(summary))
        }
    }
}

@Composable
private fun SummaryHeadline(summary: WeeklySummary) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = WeeklySummaryCopy.title(summary),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = WeeklySummaryCopy.rangeLabel(summary),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}
