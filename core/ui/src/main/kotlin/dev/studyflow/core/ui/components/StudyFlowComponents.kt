package dev.studyflow.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.studyflow.core.designsystem.theme.spacing
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/** A consistent app bar with an accessible title and optional navigation action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun StudyFlowTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
            )
        },
        modifier = modifier,
        navigationIcon = { navigationIcon?.invoke() },
        actions = actions,
    )
}

/** A selectable row for a session, subject, task, or other study-domain record. */
@Composable
public fun StudyFlowListItem(
    headline: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    overlineText: String? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(text = headline) },
        modifier =
            modifier.then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                },
            ),
        supportingContent = supportingText?.let { text -> { Text(text = text) } },
        overlineContent = overlineText?.let { text -> { Text(text = text) } },
        trailingContent = trailingContent,
    )
}

/** An action tag used to classify study content. */
@Composable
public fun StudyFlowTag(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(text = label) },
        modifier = modifier.semantics { contentDescription = label },
    )
}

/** A confirmation dialog with explicitly supplied actions. */
@Composable
public fun StudyFlowDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        title = { Text(text = title) },
        text = text?.let { value -> { Text(text = value) } },
    )
}

/** Displays a duration in a form that remains readable by TalkBack. */
@Composable
public fun DurationText(
    duration: Duration,
    modifier: Modifier = Modifier,
) {
    val display = duration.toDisplayStrings()
    Text(
        text = display.visual,
        modifier = modifier.semantics { contentDescription = display.spoken },
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Displays a user-provided time label with an explicit spoken equivalent.
 *
 * The visual label remains a [String] to support localized and relative times, which cannot be
 * reliably converted into speech here; callers therefore provide its localized spoken form.
 */
@Composable
public fun TimeText(
    time: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = time,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Explains why a permission is needed before the caller launches the platform request. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun PermissionRationaleSheet(
    title: String,
    message: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(MaterialTheme.spacing.large),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = message,
                modifier = Modifier.padding(top = MaterialTheme.spacing.small),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier =
                    Modifier
                        .align(Alignment.End)
                        .padding(top = MaterialTheme.spacing.medium),
            ) {
                action()
            }
        }
    }
}

internal fun Duration.toDisplayStrings(): DurationDisplay {
    val safeDuration = coerceAtLeast(ZERO)
    val totalMinutes = safeDuration.inWholeMinutes
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return when {
        hours > 0L && minutes > 0L -> {
            DurationDisplay(
                visual = "$hours h $minutes min",
                spoken = "$hours ${hours.unit("hour")} $minutes ${minutes.unit("minute")}",
            )
        }

        hours > 0L -> {
            DurationDisplay(visual = "$hours h", spoken = "$hours ${hours.unit("hour")}")
        }

        else -> {
            DurationDisplay(visual = "$minutes min", spoken = "$minutes ${minutes.unit("minute")}")
        }
    }
}

private fun Long.unit(singular: String): String = if (this == 1L) singular else "${singular}s"

internal data class DurationDisplay(
    val visual: String,
    val spoken: String,
)

private const val MINUTES_PER_HOUR: Long = 60
