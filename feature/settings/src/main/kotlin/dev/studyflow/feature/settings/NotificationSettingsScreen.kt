package dev.studyflow.feature.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.motion.StudyFlowMotion
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.notifications.NotificationMessageKey
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
    onOpenDataPrivacy: () -> Unit = {},
    account: @Composable () -> Unit = {},
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    var exactAlarmsDenied by remember { mutableStateOf(false) }
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
        exactAlarmsDenied =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
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
        exactAlarmsDenied = exactAlarmsDenied,
        onEvent = viewModel::onEvent,
        onOpenExactAlarmSettings = context::requestExactAlarmPermission,
        modifier = modifier,
        onOpenDataPrivacy = onOpenDataPrivacy,
        slots = NotificationSettingsSlots(account = account, statusCards = { settingsStatusCards() }),
    )
}

private fun LazyListScope.settingsStatusCards() {
    item(key = "settings-sync-status") {
        Box(Modifier.animateItem()) { SyncStatusRoute() }
    }
    item(key = "settings-log-export") {
        Box(Modifier.animateItem()) { LogExportRoute() }
    }
}

/**
 * @param slots the account, sync and log export cards, passed as slots so this screen stays renderable
 *   without a ViewModel graph — the same reason the rest of it takes state rather than fetching it.
 * @param onOpenDataPrivacy opens export, restore and account deletion (issue #78).
 */
@Composable
public fun NotificationSettingsScreen(
    state: NotificationSettingsUiState,
    onEvent: (NotificationSettingsUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    exactAlarmsDenied: Boolean = false,
    onOpenExactAlarmSettings: () -> Unit = {},
    onOpenDataPrivacy: () -> Unit = {},
    slots: NotificationSettingsSlots = NotificationSettingsSlots(),
) {
    state.rationale?.let { key ->
        NotificationRationaleDialog(key = key, onEvent = onEvent)
    }

    AnimatedContent(
        targetState = state.loaded,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            fadeIn(StudyFlowMotion.effects()) togetherWith fadeOut(StudyFlowMotion.effects())
        },
        label = "notification settings state",
    ) { loaded ->
        if (!loaded) {
            LoadingState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(MaterialTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            ) {
                item(key = "settings-account") {
                    Box(Modifier.animateItem()) { slots.account() }
                }
                item(key = "settings-title") {
                    Text(
                        text = "Notifications",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.animateItem(),
                    )
                }

                slots.statusCards(this)
                notificationPreferences(state, onEvent, exactAlarmsDenied, onOpenExactAlarmSettings)

                item(key = "data-privacy") {
                    Box(Modifier.animateItem()) {
                        DataPrivacyCard(onOpen = onOpenDataPrivacy)
                    }
                }
            }
        }
    }
}

public data class NotificationSettingsSlots(
    val account: @Composable () -> Unit = {},
    val statusCards: LazyListScope.() -> Unit = {},
)

private fun LazyListScope.notificationPreferences(
    state: NotificationSettingsUiState,
    onEvent: (NotificationSettingsUiEvent) -> Unit,
    exactAlarmsDenied: Boolean,
    onOpenExactAlarmSettings: () -> Unit,
) {
    if (state.notificationsBlocked) {
        item(key = "blocked-notifications") {
            Box(Modifier.animateItem()) {
                BlockedCard(state = state, onEvent = onEvent)
            }
        }
    }

    if (exactAlarmsDenied) {
        item(key = "exact-alarm-degradation") {
            Box(Modifier.animateItem()) {
                ExactAlarmDegradationCard(onOpenExactAlarmSettings)
            }
        }
    }

    item(key = "battery-diagnostics") {
        Box(Modifier.animateItem()) {
            BatteryDiagnosticsCard(state.batteryDiagnostics, onEvent)
        }
    }

    item(key = "weekly-summary") {
        Box(Modifier.animateItem()) {
            WeeklySummaryCard(schedule = state.weeklySummary, onEvent = onEvent)
        }
    }

    items(state.channels, key = { "channel-${it.channel.id}" }) { status ->
        ChannelCard(
            status = status,
            blockedAppWide = state.notificationsBlocked,
            onOpenSettings = { onEvent(NotificationSettingsUiEvent.OpenChannelSettings(status.channel)) },
            onPickAlarmRingtone = { onEvent(NotificationSettingsUiEvent.PickAlarmRingtone) },
            modifier = Modifier.animateItem(),
        )
    }

    item(key = "open-system-settings") {
        TextButton(
            onClick = { onEvent(NotificationSettingsUiEvent.OpenAppSettings) },
            modifier = Modifier.animateItem(),
        ) {
            Text(text = "Open system notification settings")
        }
    }
}

/** The one-time explanation shown before requesting the notification permission. */
@Composable
private fun NotificationRationaleDialog(
    key: NotificationMessageKey,
    onEvent: (NotificationSettingsUiEvent) -> Unit,
) {
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
