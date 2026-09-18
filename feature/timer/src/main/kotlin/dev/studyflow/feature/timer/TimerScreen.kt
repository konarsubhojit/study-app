package dev.studyflow.feature.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.Subject

/**
 * Hosts [TimerViewModel] behind the screen, following the same wiring [TaskDetailRoute] uses in
 * `:feature:tasks`.
 */
@Composable
public fun TimerRoute(
    modifier: Modifier = Modifier,
    taskId: String? = null,
    subjectId: String? = null,
    viewModel: TimerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var studyNowHandled by rememberSaveable(taskId, subjectId) { mutableStateOf(false) }
    LaunchedEffect(taskId, subjectId) {
        if (taskId != null && !studyNowHandled) {
            studyNowHandled = true
            viewModel.onEvent(TimerUiEvent.StudyNowRequested(taskId, subjectId))
        }
    }
    TimerScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

/**
 * The timer screen: an unmistakable elapsed display, one-tap Start/Pause/Resume/Stop, a subject
 * picker and notes for a new session (issue konarsubhojit/study-app#33).
 *
 * The primary control sits at the bottom of the screen, reachable one-handed on a large phone; the
 * elapsed digits use tabular, monospaced figures so a changing second never reflows the layout.
 */
@Composable
public fun TimerScreen(
    state: TimerUiState,
    onEvent: (TimerUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    KeepScreenOn(enabled = state.keepScreenOn)

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(MaterialTheme.spacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large, Alignment.CenterVertically),
        ) {
            PhaseAnnouncement(state = state)
            ElapsedDisplay(elapsedSeconds = state.elapsedSeconds)
            if (state.hasUnverifiedTime) {
                UnverifiedTimeNotice()
            }
            StudyTaskContext(state = state, onEvent = onEvent)
            SubjectPicker(state = state, onEvent = onEvent)
            NotesField(state = state, onEvent = onEvent)
        }

        BottomControls(state = state, onEvent = onEvent)
    }
}

@Composable
private fun StudyTaskContext(
    state: TimerUiState,
    onEvent: (TimerUiEvent) -> Unit,
) {
    if (state.phase != TimerPhase.IDLE) return
    state.selectedTask?.let {
        Text(text = "Studying now: ${it.title}", style = MaterialTheme.typography.titleMedium)
        return
    }
    state.suggestedTask?.let { task ->
        Button(onClick = { onEvent(TimerUiEvent.StudyNowRequested(task.id, task.subjectId)) }) {
            Text(text = "Study now: ${task.title}")
        }
    }
}

/**
 * A live region that announces only when [TimerUiState.phase] (or the unverified-time flag)
 * actually changes, never on every elapsed-second tick — the composable recomposes each second,
 * but its text is unchanged in between, so TalkBack has nothing new to speak.
 */
@Composable
private fun PhaseAnnouncement(state: TimerUiState) {
    Text(
        text = state.phase.announcement(state.hasUnverifiedTime),
        style = MaterialTheme.typography.titleMedium,
        modifier =
            Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
    )
}

private fun TimerPhase.announcement(hasUnverifiedTime: Boolean): String =
    when (this) {
        TimerPhase.IDLE -> "Ready to start"
        TimerPhase.RUNNING -> "Running"
        TimerPhase.PAUSED -> if (hasUnverifiedTime) "Paused — recovered after reboot" else "Paused"
    }

/**
 * Large, legible elapsed time in `H:MM:SS`, using tabular monospaced digits so neither a digit
 * change nor a minute/hour rollover ever reflows the surrounding layout.
 *
 * Deliberately excluded from the accessibility tree's automatic re-announcement: a fixed
 * [contentDescription] is available on focus, but with no `liveRegion` it is never spoken every
 * second, which is exactly what "not every second" requires — [PhaseAnnouncement] carries the
 * state changes TalkBack actually needs to hear.
 */
@Composable
private fun ElapsedDisplay(elapsedSeconds: Long) {
    Text(
        text = elapsedSeconds.toClockText(),
        style =
            MaterialTheme.typography.displayLarge.copy(
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            ),
        modifier =
            Modifier.clearAndSetSemantics {
                contentDescription = "Elapsed time ${elapsedSeconds.toSpokenDuration()}"
            },
    )
}

@Composable
private fun UnverifiedTimeNotice() {
    Text(
        text = "Part of this session happened while the device was off and could not be verified.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SubjectPicker(
    state: TimerUiState,
    onEvent: (TimerUiEvent) -> Unit,
) {
    if (state.subjects.isEmpty()) return
    val editable = state.phase == TimerPhase.IDLE
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        items(state.subjects) { subject ->
            SubjectChip(
                subject = subject,
                selected = subject.id == state.selectedSubjectId,
                enabled = editable,
                onClick = { onEvent(TimerUiEvent.SubjectSelected(subject.id)) },
            )
        }
    }
}

@Composable
private fun SubjectChip(
    subject: Subject,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        label = { Text(text = subject.name) },
        modifier = Modifier.semantics { contentDescription = "Subject ${subject.name}" },
    )
}

@Composable
private fun NotesField(
    state: TimerUiState,
    onEvent: (TimerUiEvent) -> Unit,
) {
    OutlinedTextField(
        value = state.note,
        onValueChange = { onEvent(TimerUiEvent.NoteChanged(it)) },
        label = { Text(text = "Session notes") },
        enabled = state.phase == TimerPhase.IDLE,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Session notes" },
    )
}

@Composable
private fun BottomControls(
    state: TimerUiState,
    onEvent: (TimerUiEvent) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        KeepScreenOnToggle(state = state, onEvent = onEvent)
        PrimaryControls(state = state, onEvent = onEvent)
    }
}

@Composable
private fun PrimaryControls(
    state: TimerUiState,
    onEvent: (TimerUiEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        when (state.phase) {
            TimerPhase.IDLE -> {
                PrimaryButton(
                    label = "Start",
                    contentDescription = "Start study session",
                    onClick = { onEvent(TimerUiEvent.StartRequested) },
                )
            }

            TimerPhase.RUNNING -> {
                PrimaryButton(
                    label = "Pause",
                    contentDescription = "Pause study session",
                    onClick = { onEvent(TimerUiEvent.PauseRequested) },
                    modifier = Modifier.weight(1f),
                )
                StopButton(onEvent = onEvent, modifier = Modifier.weight(1f))
            }

            TimerPhase.PAUSED -> {
                PrimaryButton(
                    label = "Resume",
                    contentDescription = "Resume study session",
                    onClick = { onEvent(TimerUiEvent.ResumeRequested) },
                    modifier = Modifier.weight(1f),
                )
                StopButton(onEvent = onEvent, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { this.contentDescription = contentDescription },
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StopButton(
    onEvent: (TimerUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = { onEvent(TimerUiEvent.StopRequested) },
        modifier = modifier.semantics { contentDescription = "Stop study session" },
    ) {
        Text(text = "Stop", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun KeepScreenOnToggle(
    state: TimerUiState,
    onEvent: (TimerUiEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = "Keep screen on", style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = state.keepScreenOn,
            onCheckedChange = { onEvent(TimerUiEvent.KeepScreenOnChanged(it)) },
            modifier = Modifier.semantics { contentDescription = "Keep screen on while timer runs" },
        )
    }
}

/** Applies [FLAG_KEEP_SCREEN_ON]-equivalent behaviour via the composition's own [android.view.View]. */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

private fun Long.toClockText(): String {
    val safeSeconds = coerceAtLeast(0L)
    val hours = safeSeconds / SECONDS_PER_HOUR
    val minutes = (safeSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = safeSeconds % SECONDS_PER_MINUTE
    return "%d:%02d:%02d".format(hours, minutes, seconds)
}

private fun Long.toSpokenDuration(): String {
    val safeSeconds = coerceAtLeast(0L)
    val hours = safeSeconds / SECONDS_PER_HOUR
    val minutes = (safeSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = safeSeconds % SECONDS_PER_MINUTE
    return listOfNotNull(
        (hours to "hour").takeIf { hours > 0 },
        (minutes to "minute").takeIf { minutes > 0 },
        (seconds to "second").takeIf { seconds > 0 || (hours == 0L && minutes == 0L) },
    ).joinToString(separator = " ") { (amount, unit) -> "$amount ${unit.pluralize(amount)}" }
}

private fun String.pluralize(amount: Long): String = if (amount == 1L) this else "${this}s"

private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_MINUTE = 60L
