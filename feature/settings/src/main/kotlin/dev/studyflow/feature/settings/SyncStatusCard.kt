package dev.studyflow.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import dev.studyflow.core.datastore.UploadNetwork
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

            UploadNetworkChoice(
                selected = state.uploadNetwork,
                onSelect = { network -> onEvent(SyncStatusUiEvent.SetUploadNetwork(network)) },
            )
        }
    }
}

/**
 * Which networks uploads may use.
 *
 * Wi-Fi only stays the default — a materials library is files, and an unasked-for 50 MB upload on
 * a metered plan costs the user real money — but the choice is visible and one tap away, so a
 * device that is waiting is waiting because the user said so, not because nobody asked.
 */
@Composable
private fun UploadNetworkChoice(
    selected: UploadNetwork,
    onSelect: (UploadNetwork) -> Unit,
) {
    Text(text = "Upload files over", style = MaterialTheme.typography.titleSmall)
    val current = selected.asChoice
    uploadNetworkOptions.forEach { (network, label) ->
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = network == current,
                        role = Role.RadioButton,
                        onClick = { onSelect(network) },
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            RadioButton(selected = network == current, onClick = null)
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * `MANUAL` shares `ANY_NETWORK`'s upload constraint — it limits when sync *runs*, not which
 * network an upload may use — so it reads as the same choice here rather than as no choice at all.
 */
private val UploadNetwork.asChoice: UploadNetwork
    get() = if (this == UploadNetwork.MANUAL) UploadNetwork.ANY_NETWORK else this

private val uploadNetworkOptions: List<Pair<UploadNetwork, String>> =
    listOf(
        UploadNetwork.WIFI_ONLY to "Wi-Fi only",
        UploadNetwork.ANY_NETWORK to "Wi-Fi or mobile data",
    )

private fun SyncStatusUiState.pendingSummary(): String =
    when (pendingCount) {
        0 -> "Everything on this device has been sent."
        1 -> "1 change waiting to be sent."
        else -> "$pendingCount changes waiting to be sent."
    }

internal fun SyncStatusUiState.lastSuccessSummary(): String {
    val lastSuccess = lastSuccessAt
    return when {
        lastSuccess != null -> "Last successful sync: ${lastSuccess.toLocalLabel()}"
        hasNeverRun -> "Sync has not run on this device yet, so nothing has been sent."
        status.lastAttemptAt != null -> "This device has not synced successfully yet."
        else -> "This device has not synced yet."
    }
}

private fun Instant.toLocalLabel(): String {
    val local = toLocalDateTime(TimeZone.currentSystemDefault())
    val minute = local.minute.toString().padStart(2, '0')
    return "${local.date} ${local.hour}:$minute"
}
