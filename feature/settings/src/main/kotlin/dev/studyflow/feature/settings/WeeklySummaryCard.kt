package dev.studyflow.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.studyflow.core.datastore.WeeklySummarySchedule
import dev.studyflow.core.designsystem.theme.spacing

/**
 * The weekly recap opt-in, with the day and time it is delivered at (issue #63).
 *
 * Off by default and switched off again with one tap, which cancels the scheduled work rather than
 * leaving it queued to do nothing — "opting out stops it immediately and permanently".
 */
@Composable
internal fun WeeklySummaryCard(
    schedule: WeeklySummarySchedule,
    onEvent: (NotificationSettingsUiEvent) -> Unit,
) {
    var showTimePicker by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Weekly summary", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = schedule.enabled,
                    onCheckedChange = { onEvent(NotificationSettingsUiEvent.WeeklySummaryEnabled(it)) },
                    modifier = Modifier.semantics { contentDescription = "Weekly summary" },
                )
            }
            Text(
                text = "A recap of your hours, top subjects, streak and goal — once a week.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (schedule.enabled) {
                WeeklySummaryDayRow(schedule = schedule, onEvent = onEvent)
                TextButton(onClick = { showTimePicker = true }) {
                    Text(text = "Delivered at ${schedule.timeLabel()}")
                }
            }
        }
    }

    if (showTimePicker) {
        WeeklySummaryTimePickerDialog(
            schedule = schedule,
            onDismissRequest = { showTimePicker = false },
            onConfirm = { hour, minute ->
                showTimePicker = false
                onEvent(
                    NotificationSettingsUiEvent.WeeklySummaryTimeChanged(
                        isoDayOfWeek = schedule.isoDayOfWeek,
                        hour = hour,
                        minute = minute,
                    ),
                )
            },
        )
    }
}

@Composable
private fun WeeklySummaryDayRow(
    schedule: WeeklySummarySchedule,
    onEvent: (NotificationSettingsUiEvent) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        items(DAY_NAMES.size, key = { it }) { index ->
            val isoDay = index + 1
            FilterChip(
                selected = schedule.isoDayOfWeek == isoDay,
                onClick = {
                    onEvent(
                        NotificationSettingsUiEvent.WeeklySummaryTimeChanged(
                            isoDayOfWeek = isoDay,
                            hour = schedule.hour,
                            minute = schedule.minute,
                        ),
                    )
                },
                label = { Text(DAY_NAMES[index]) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeeklySummaryTimePickerDialog(
    schedule: WeeklySummarySchedule,
    onDismissRequest: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val pickerState = rememberTimePickerState(initialHour = schedule.hour, initialMinute = schedule.minute)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = "Delivery time") },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour, pickerState.minute) }) {
                Text(text = "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = "Cancel")
            }
        },
    )
}

private fun WeeklySummarySchedule.timeLabel(): String =
    "${DAY_NAMES[isoDayOfWeek - 1]} ${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

/** ISO order: index 0 is Monday, matching [WeeklySummarySchedule.isoDayOfWeek] minus one. */
private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
