package dev.studyflow.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.theme.spacing

/**
 * "Data & privacy": take your data with you, bring it back, or delete it (issue #78).
 *
 * The two pickers are launched from here rather than from the ViewModel because only an activity
 * may start them; the ViewModel decides *that* one should open and learns the answer as an event,
 * which keeps the whole flow testable without an Android runtime.
 */
@Composable
public fun DataPrivacyRoute(
    modifier: Modifier = Modifier,
    viewModel: DataPrivacyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val createDocumentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ARCHIVE_MIME_TYPE)) { uri ->
            viewModel.onEvent(DataPrivacyUiEvent.ExportDestinationChosen(uri?.toString()))
        }
    val openDocumentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            viewModel.onEvent(DataPrivacyUiEvent.ImportSourceChosen(uri?.toString()))
        }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DataPrivacyUiEffect.CreateArchiveDocument -> createDocumentLauncher.launch(effect.suggestedName)
                DataPrivacyUiEffect.OpenArchiveDocument -> openDocumentLauncher.launch(ARCHIVE_PICKER_TYPES)
            }
        }
    }

    DataPrivacyScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

@Composable
public fun DataPrivacyScreen(
    state: DataPrivacyUiState,
    onEvent: (DataPrivacyUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.confirmingDeletion) {
        DeleteAccountDialog(onEvent = onEvent)
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        Text(text = DataPrivacyCopy.TITLE, style = MaterialTheme.typography.headlineSmall)

        if (state.isBusy) {
            LinearProgressIndicator(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = DataPrivacyCopy.BUSY },
            )
        }

        state.message?.let { message ->
            MessageCard(message = message, onEvent = onEvent)
        }

        ActionCard(
            title = DataPrivacyCopy.EXPORT_TITLE,
            body = DataPrivacyCopy.EXPORT_BODY,
            action = DataPrivacyCopy.EXPORT_ACTION,
            enabled = !state.isBusy,
            onClick = { onEvent(DataPrivacyUiEvent.ExportRequested) },
        )

        ActionCard(
            title = DataPrivacyCopy.IMPORT_TITLE,
            body = DataPrivacyCopy.IMPORT_BODY,
            action = DataPrivacyCopy.IMPORT_ACTION,
            enabled = !state.isBusy,
            onClick = { onEvent(DataPrivacyUiEvent.ImportRequested) },
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                Text(text = DataPrivacyCopy.DELETE_TITLE, style = MaterialTheme.typography.titleMedium)
                Text(text = DataPrivacyCopy.deleteBody(), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = { onEvent(DataPrivacyUiEvent.DeleteAccountRequested) },
                    enabled = !state.isBusy,
                ) {
                    Text(text = DataPrivacyCopy.DELETE_ACTION)
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    body: String,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick, enabled = enabled) {
                Text(text = action)
            }
        }
    }
}

@Composable
private fun MessageCard(
    message: DataPrivacyMessage,
    onEvent: (DataPrivacyUiEvent) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(text = DataPrivacyCopy.describe(message), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onEvent(DataPrivacyUiEvent.MessageDismissed) }) {
                Text(text = DataPrivacyCopy.DISMISS)
            }
        }
    }
}

/**
 * The confirmation step.
 *
 * Deliberately a dialog with the destructive action as the *dismissible* button's neighbour and
 * the plain-language consequence spelled out: this is the one screen in the app where a mis-tap
 * cannot be undone.
 */
@Composable
private fun DeleteAccountDialog(onEvent: (DataPrivacyUiEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(DataPrivacyUiEvent.DeleteAccountDismissed) },
        title = { Text(text = DataPrivacyCopy.DELETE_CONFIRM_TITLE) },
        text = { Text(text = DataPrivacyCopy.deleteConfirmBody()) },
        confirmButton = {
            TextButton(onClick = { onEvent(DataPrivacyUiEvent.DeleteAccountConfirmed) }) {
                Text(text = DataPrivacyCopy.DELETE_CONFIRM_ACTION)
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(DataPrivacyUiEvent.DeleteAccountDismissed) }) {
                Text(text = DataPrivacyCopy.CANCEL)
            }
        },
    )
}

/** What `ACTION_CREATE_DOCUMENT` creates; zip is the archive format (see `docs/data-lifecycle.md`). */
private const val ARCHIVE_MIME_TYPE = "application/zip"

/**
 * What the picker will let the user choose.
 *
 * `application/octet-stream` is included because some providers report a `.zip` that way, and a
 * user staring at their own archive greyed out has no way to tell why.
 */
private val ARCHIVE_PICKER_TYPES = arrayOf(ARCHIVE_MIME_TYPE, "application/octet-stream")
