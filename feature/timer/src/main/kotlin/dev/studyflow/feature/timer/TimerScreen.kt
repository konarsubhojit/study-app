package dev.studyflow.feature.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.ui.state.EmptyState

@Composable
public fun TimerScreen(
    state: TimerUiState,
    modifier: Modifier = Modifier,
) {
    if (state.elapsedSeconds == 0) {
        EmptyState(message = "Start a study session when you're ready.", modifier = modifier)
    } else {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(MaterialTheme.spacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium, Alignment.CenterVertically),
        ) {
            Text(text = "${state.elapsedSeconds} seconds", style = MaterialTheme.typography.headlineMedium)
        }
    }
}
