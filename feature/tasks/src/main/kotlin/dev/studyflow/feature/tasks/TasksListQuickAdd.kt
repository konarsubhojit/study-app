package dev.studyflow.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.studyflow.core.designsystem.theme.spacing
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickAddBar(
    title: String,
    dueDate: QuickAddDueDate,
    onTitleChange: (String) -> Unit,
    onDueDateChange: (QuickAddDueDate) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
    ) {
        QuickAddInputRow(title = title, onTitleChange = onTitleChange, onSubmit = onSubmit)
        QuickAddDueDateRow(
            dueDate = dueDate,
            onDueDateChange = onDueDateChange,
            onCustomDateRequest = { showDatePicker = true },
        )
    }

    if (showDatePicker) {
        QuickAddDatePickerDialog(
            onDueDateChange = onDueDateChange,
            onDismissRequest = { showDatePicker = false },
        )
    }
}

@Composable
private fun QuickAddInputRow(
    title: String,
    onTitleChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            label = { Text(text = "Add a task") },
            singleLine = true,
            modifier =
                Modifier
                    .weight(1f)
                    .semantics { contentDescription = "New task title" },
        )
        Button(
            onClick = onSubmit,
            enabled = title.isNotBlank(),
            modifier =
                Modifier.semantics {
                    contentDescription = "Add task"
                    role = Role.Button
                },
        ) {
            Text(text = "Add")
        }
    }
}

@Composable
private fun QuickAddDueDateRow(
    dueDate: QuickAddDueDate,
    onDueDateChange: (QuickAddDueDate) -> Unit,
    onCustomDateRequest: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        modifier = Modifier.padding(top = MaterialTheme.spacing.small),
    ) {
        DueDateChip(
            label = "Today",
            selected = dueDate == QuickAddDueDate.Today,
            onClick = { onDueDateChange(dueDate.toggled(QuickAddDueDate.Today)) },
        )
        DueDateChip(
            label = "Tomorrow",
            selected = dueDate == QuickAddDueDate.Tomorrow,
            onClick = { onDueDateChange(dueDate.toggled(QuickAddDueDate.Tomorrow)) },
        )
        DueDateChip(
            label = "Next week",
            selected = dueDate == QuickAddDueDate.NextWeek,
            onClick = { onDueDateChange(dueDate.toggled(QuickAddDueDate.NextWeek)) },
        )
        DueDateChip(
            label = (dueDate as? QuickAddDueDate.Custom)?.date?.toString() ?: "Custom",
            selected = dueDate is QuickAddDueDate.Custom,
            onClick = {
                if (dueDate is QuickAddDueDate.Custom) {
                    onDueDateChange(QuickAddDueDate.None)
                } else {
                    onCustomDateRequest()
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAddDatePickerDialog(
    onDueDateChange: (QuickAddDueDate) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val pickerState = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onDueDateChange(QuickAddDueDate.Custom(millis.toUtcLocalDate()))
                    }
                    onDismissRequest()
                },
            ) {
                Text(text = "Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = "Cancel")
            }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

private fun Long.toUtcLocalDate(): LocalDate = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date

/** Tapping a selected chip again clears it, which is the only way to get back to "no due date". */
private fun QuickAddDueDate.toggled(target: QuickAddDueDate): QuickAddDueDate =
    if (this == target) QuickAddDueDate.None else target

@Composable
private fun DueDateChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label) },
        modifier = Modifier.semantics { contentDescription = "$label due date" },
    )
}
