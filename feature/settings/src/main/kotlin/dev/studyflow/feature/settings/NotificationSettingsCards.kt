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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.notifications.NotificationChannelStatus
import dev.studyflow.core.notifications.StudyFlowNotificationChannel

/**
 * The way into "Data & privacy" (issue #78).
 *
 * A card rather than a buried menu item: a user who wants their data out — or gone — should not
 * have to hunt for the control, and the deletion route has to be findable to be honest.
 */
@Composable
internal fun DataPrivacyCard(onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(text = DataPrivacyCopy.TITLE, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Export your data, restore it from an archive, or delete your account.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpen) {
                Text(text = "Open data & privacy")
            }
        }
    }
}

@Composable
internal fun BlockedCard(
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
internal fun ExactAlarmDegradationCard(onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(text = "Reminders may be less precise", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Allow exact alarms to receive reminders closer to their scheduled time.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpenSettings) {
                Text(text = "Open exact alarm settings")
            }
        }
    }
}

@Composable
internal fun BatteryDiagnosticsCard(
    diagnostics: BatteryDiagnosticsSnapshot,
    onEvent: (NotificationSettingsUiEvent) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(text = "Battery diagnostics", style = MaterialTheme.typography.titleMedium)
            Text(
                text = diagnostics.impactText(),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (diagnostics.needsAttention) {
                Text(
                    text = diagnostics.oemGuidance(),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { onEvent(NotificationSettingsUiEvent.OpenBatterySettings) }) {
                    Text(text = "Open battery settings")
                }
            }
        }
    }
}

@Composable
internal fun ChannelCard(
    status: NotificationChannelStatus,
    blockedAppWide: Boolean,
    onOpenSettings: () -> Unit,
    onPickAlarmRingtone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
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
