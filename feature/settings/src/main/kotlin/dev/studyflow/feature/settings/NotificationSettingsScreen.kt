package dev.studyflow.feature.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.notifications.NotificationChannelStatus
import dev.studyflow.core.notifications.StudyFlowNotificationChannel
import dev.studyflow.core.ui.state.LoadingState

/**
 * Notification settings, wired to the platform.
 *
 * The screen re-reads system state on every resume, because the two buttons it offers both send
 * the user to system settings; coming back to a screen that still showed the old answer would make
 * the app look broken exactly when the user had just fixed something.
 */
@Composable
@SuppressLint("InlinedApi") // RequestPermission().launch is safe to call with POST_NOTIFICATIONS
// below API 33; the system reports the permission as already granted on older platforms.
public fun NotificationSettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            viewModel.onEvent(
                NotificationSettingsUiEvent.PermissionResult(
                    shouldShowRationale = activity.shouldExplainNotifications(),
                ),
            )
        }
    val ringtonePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri =
                result.data
                    ?.getParcelableRingtoneUri()
                    ?.toString()
            viewModel.onEvent(NotificationSettingsUiEvent.AlarmRingtonePicked(uri))
        }

    LifecycleResumeEffect(activity) {
        viewModel.onEvent(NotificationSettingsUiEvent.Refresh(activity.shouldExplainNotifications()))
        onPauseOrDispose {}
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                NotificationSettingsUiEffect.RequestPermission -> {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                is NotificationSettingsUiEffect.OpenSystemSettings -> {
                    context.startSettings(effect)
                }

                is NotificationSettingsUiEffect.LaunchRingtonePicker -> {
                    ringtonePickerLauncher.launch(ringtonePickerIntent(effect.currentUri))
                }
            }
        }
    }

    NotificationSettingsScreen(
        state = state,
        onEvent = viewModel::onEvent,
        modifier = modifier,
    )
}

@Composable
public fun NotificationSettingsScreen(
    state: NotificationSettingsUiState,
    onEvent: (NotificationSettingsUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    state.rationale?.let { key ->
        AlertDialog(
            onDismissRequest = { onEvent(NotificationSettingsUiEvent.RationaleDismissed) },
            title = { Text(text = "Turn on notifications") },
            text = { Text(text = NotificationCopy.rationale(key)) },
            confirmButton = {
                TextButton(onClick = { onEvent(NotificationSettingsUiEvent.RationaleAccepted) }) {
                    Text(text = "Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(NotificationSettingsUiEvent.RationaleDismissed) }) {
                    Text(text = "Not now")
                }
            },
        )
    }

    if (!state.loaded) {
        LoadingState(modifier = modifier)
        return
    }

    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        item {
            Text(text = "Notifications", style = MaterialTheme.typography.headlineSmall)
        }

        if (state.notificationsBlocked) {
            item {
                BlockedCard(state = state, onEvent = onEvent)
            }
        }

        items(state.channels, key = { it.channel.id }) { status ->
            ChannelCard(
                status = status,
                blockedAppWide = state.notificationsBlocked,
                onOpenSettings = { onEvent(NotificationSettingsUiEvent.OpenChannelSettings(status.channel)) },
                onPickAlarmRingtone = { onEvent(NotificationSettingsUiEvent.PickAlarmRingtone) },
            )
        }

        item {
            TextButton(onClick = { onEvent(NotificationSettingsUiEvent.OpenAppSettings) }) {
                Text(text = "Open system notification settings")
            }
        }
    }
}

@Composable
private fun BlockedCard(
    state: NotificationSettingsUiState,
    onEvent: (NotificationSettingsUiEvent) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(text = "Notifications are off", style = MaterialTheme.typography.titleMedium)
            state.degradation?.let {
                Text(
                    text = NotificationCopy.degradation(it),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = { onEvent(NotificationSettingsUiEvent.EnableNotifications) }) {
                Text(text = if (state.canRequestPermission) "Turn on" else "Open settings")
            }
        }
    }
}

@Composable
private fun ChannelCard(
    status: NotificationChannelStatus,
    blockedAppWide: Boolean,
    onOpenSettings: () -> Unit,
    onPickAlarmRingtone: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            Text(text = status.channel.channelName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = NotificationCopy.channelPurpose(status.channel),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (status.channel == StudyFlowNotificationChannel.ALARMS) {
                Text(
                    text = NotificationCopy.ALARM_DND_EXPLANATION,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text =
                    if (blockedAppWide) {
                        "Blocked while notifications are off"
                    } else {
                        NotificationCopy.channelState(status)
                    },
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(
                onClick = onOpenSettings,
                modifier =
                    Modifier.semantics {
                        contentDescription = "Change ${status.channel.channelName} in system settings"
                    },
            ) {
                Text(text = "Change in system settings")
            }
            if (status.channel == StudyFlowNotificationChannel.ALARMS) {
                TextButton(onClick = onPickAlarmRingtone) {
                    Text(text = "Choose alarm sound")
                }
            }
        }
    }
}

/** The picker's own `EXTRA_RINGTONE_PICKED_URI`, wherever `RingtoneManager` put it in [this]. */
private fun Intent.getParcelableRingtoneUri(): android.net.Uri? =
    @Suppress("DEPRECATION")
    getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)

private fun ringtonePickerIntent(currentUri: String): Intent =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        putExtra(
            RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        )
        if (currentUri.isNotBlank()) {
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri.toUri())
        }
    }

/**
 * Asks the platform whether a refusal can still be explained.
 *
 * `false` outside an activity and below Android 13, which is correct in both cases: there is
 * nothing to explain when there is no permission to ask for, and no window to explain it in.
 */
private fun Activity?.shouldExplainNotifications(): Boolean =
    this != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)

/**
 * Opens a system settings page, tolerating the ones that do not exist.
 *
 * Per-channel settings are missing on some OEM builds. Losing the shortcut is survivable; crashing
 * on the way to a settings screen is not, so the app-level page is tried next and a device with
 * neither simply leaves the user where they were.
 */
private fun Context.startSettings(effect: NotificationSettingsUiEffect.OpenSystemSettings) {
    if (start(effect.intent)) return
    effect.fallbackIntent?.let(::start)
}

private fun Context.start(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
