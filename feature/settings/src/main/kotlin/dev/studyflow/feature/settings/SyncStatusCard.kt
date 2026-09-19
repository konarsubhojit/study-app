package dev.studyflow.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.theme.spacing
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** The sync card, wired to its ViewModel; rendered inside the settings list. */
@Composable
public fun SyncStatusRoute(
    modifier: Modifier = Modifier,
    viewModel: SyncStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SyncStatusCard(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

/**
 * What sync is doing, in the three facts a user can act on (issue #55).
 *
 * The error is shown as plain text rather than as a warning the user must dismiss: sync failing
 * once is ordinary on a phone, and nothing is lost when it does — the queue is still there. What
 * would not be ordinary is *not knowing*, which is what this card exists to prevent.
 */
@Composable
public fun SyncStatusCard(
    state: SyncStatusUiState,
    onEvent: (SyncStatusUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(text = "Sync", style = MaterialTheme.typography.titleMedium)
            Text(
                text = state.pendingSummary(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { contentDescription = state.pendingSummary() },
            )
            Text(
                text = state.lastSuccessSummary(),
                style = MaterialTheme.typography.bodySmall,
            )
            state.lastError?.let { error ->
                Text(
                    text = "Last attempt failed: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = { onEvent(SyncStatusUiEvent.SyncNow) }) {
                Text(text = "Sync now")
            }
        }
    }
}

private fun SyncStatusUiState.pendingSummary(): String =
    when (pendingCount) {
        0 -> "Everything on this device has been sent."
        1 -> "1 change waiting to be sent."
        else -> "$pendingCount changes waiting to be sent."
    }

private fun SyncStatusUiState.lastSuccessSummary(): String =
    lastSuccessAt?.let { "Last successful sync: ${it.toLocalLabel()}" }
        ?: "This device has not synced yet."

private fun Instant.toLocalLabel(): String {
    val local = toLocalDateTime(TimeZone.currentSystemDefault())
    val minute = local.minute.toString().padStart(2, '0')
    return "${local.date} ${local.hour}:$minute"
}
