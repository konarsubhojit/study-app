package dev.studyflow.feature.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.domain.result.DomainError

/** The log export card, wired to its ViewModel; rendered inside the settings list. */
@Composable
public fun LogExportRoute(
    modifier: Modifier = Modifier,
    viewModel: LogExportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is LogExportUiEffect.ShareFile -> context.startActivity(shareLogsChooser(effect.uri))
            }
        }
    }

    LogExportCard(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

/**
 * "Export logs": the user's way to hand over what a release build kept to itself.
 *
 * Release logging is deliberately almost silent in logcat, which is what keeps a shared log buffer
 * free of email addresses and document names. This card is the other half of that bargain: the
 * detail exists, it stays on the device, and it leaves only when its owner says so.
 */
@Composable
public fun LogExportCard(
    state: LogExportUiState,
    onEvent: (LogExportUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(text = "Diagnostics", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Save a copy of this app's recent activity to share when reporting a problem.",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.message?.let { message ->
                Text(
                    text = message.text(),
                    style = MaterialTheme.typography.bodySmall,
                    color = message.colour(),
                    modifier = Modifier.semantics { contentDescription = message.text() },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                TextButton(
                    onClick = { onEvent(LogExportUiEvent.ExportRequested) },
                    enabled = !state.exporting,
                ) {
                    Text(text = if (state.exporting) "Exporting…" else "Export logs")
                }
                if (state.shareUri != null) {
                    TextButton(onClick = { onEvent(LogExportUiEvent.ShareRequested) }) {
                        Text(text = "Share")
                    }
                }
            }
        }
    }
}

@Composable
private fun LogExportMessage.colour() =
    when (this) {
        is LogExportMessage.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

internal fun LogExportMessage.text(): String =
    when (this) {
        is LogExportMessage.Exported -> {
            if (inDownloads) {
                "Saved to Downloads as $fileName."
            } else {
                "Saved as $fileName. Use Share to send it."
            }
        }

        LogExportMessage.Empty -> {
            "There is nothing to export yet."
        }

        is LogExportMessage.Failed -> {
            "The logs could not be saved. ${error.reason()}"
        }
    }

/**
 * Why it failed, in terms a user can act on.
 *
 * No exception text and no status code: the detail belongs in the log this card exports, not in
 * the card (ADR 0014).
 */
private fun DomainError.reason(): String =
    when (this) {
        DomainError.Network -> "Please try again."
        DomainError.Storage -> "There was not enough space, or the file could not be written."
        DomainError.Permission -> "StudyFlow is not allowed to use that location."
        DomainError.Validation -> "Please try again."
        DomainError.Unknown -> "Please try again."
    }

private fun shareLogsChooser(uri: String): Intent {
    val share =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri.toUri())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return Intent.createChooser(share, "Share StudyFlow logs").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
