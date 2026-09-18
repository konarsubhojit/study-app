package dev.studyflow.feature.timer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.studyflow.core.common.time.AnchoredClock
import dev.studyflow.core.domain.session.SessionCommandResult
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.subjects.SubjectRepository
import dev.studyflow.core.domain.tasks.TaskRepository
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.domain.timer.TimerEngine
import dev.studyflow.core.domain.timer.TimerRejection
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subject
import dev.studyflow.core.ui.mvi.MviViewModel
import dev.studyflow.core.ui.mvi.UiEffect
import dev.studyflow.core.ui.mvi.UiEvent
import dev.studyflow.core.ui.mvi.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** What the timer screen shows: an unmistakable, one-of-three state. */
public enum class TimerPhase {
    /** No session exists; the screen offers Start. */
    IDLE,

    /** A session is counting; the screen offers Pause and Stop. */
    RUNNING,

    /** A session exists but is not counting; the screen offers Resume and Stop. */
    PAUSED,
}

/**
 * Everything the timer screen renders.
 *
 * [subjects] and the draft [selectedSubjectId]/[note] are only meaningful before a session starts;
 * once one is active, [selectedSubjectId] and [note] mirror the session's own (immutable) metadata
 * so the screen always shows what was actually recorded, not a stale draft.
 */
public data class TimerUiState(
    val phase: TimerPhase = TimerPhase.IDLE,
    val elapsedSeconds: Long = 0,
    /** True once a reboot (or other unbridgeable clock gap) forced part of this session unverified. */
    val hasUnverifiedTime: Boolean = false,
    val subjects: List<Subject> = emptyList(),
    val selectedTask: StudyTask? = null,
    val suggestedTask: StudyTask? = null,
    val selectedSubjectId: String? = null,
    val note: String = "",
    val keepScreenOn: Boolean = false,
) : UiState

public sealed interface TimerUiEvent : UiEvent {
    /** Re-derives elapsed time from the log; also used to drive the once-a-second display tick. */
    public data object Refresh : TimerUiEvent

    public data object StartRequested : TimerUiEvent

    public data class StudyNowRequested(
        val taskId: String,
        val subjectId: String?,
    ) : TimerUiEvent

    public data class RouteStudyNowRequested(
        val taskId: String,
        val subjectId: String?,
    ) : TimerUiEvent

    public data object PauseRequested : TimerUiEvent

    public data object ResumeRequested : TimerUiEvent

    public data object StopRequested : TimerUiEvent

    public data class SubjectSelected(
        val subjectId: String?,
    ) : TimerUiEvent

    public data class NoteChanged(
        val note: String,
    ) : TimerUiEvent

    public data class KeepScreenOnChanged(
        val keepScreenOn: Boolean,
    ) : TimerUiEvent
}

public sealed interface TimerUiEffect : UiEffect {
    /** A control was tapped in a state the timer engine refuses, e.g. double-tapping Stop. */
    public data class CommandRejected(
        val reason: TimerRejection,
    ) : TimerUiEffect
}

/**
 * Drives the timer screen: Start/Pause/Resume/Stop, subject and note selection for a new session,
 * and reboot recovery, all backed by [SessionRepository]'s append-only event log (see
 * [TimerEngine]'s KDoc for why nothing here ticks a counter).
 *
 * Elapsed time is re-derived, never accumulated: [refreshes] combined with a once-a-second ticker
 * while [TimerPhase.RUNNING] simply asks [SessionRepository.activeState] for a fresh fold and
 * re-renders — a missed tick (screen off, process backgrounded) never desyncs the display, because
 * the next one reads the same authoritative log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
public class TimerViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val sessionRepository: SessionRepository,
        subjectRepository: SubjectRepository,
        taskRepository: TaskRepository,
        private val anchoredClock: AnchoredClock,
    ) : MviViewModel<TimerUiEvent, TimerUiEffect>(savedStateHandle) {
        // Refreshes are rendering invalidations; if several arrive together, one fresh re-read is enough.
        private val refreshes =
            MutableSharedFlow<Unit>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        private val draftSubjectId = MutableStateFlow<String?>(null)
        private val draftTaskId = MutableStateFlow<String?>(null)
        private val draftNote = MutableStateFlow("")
        private val keepScreenOn = MutableStateFlow(false)
        private val tasks = taskRepository.observeTasks().stateInViewModel(emptyList())

        private val activeSession: Flow<StudySession?> = sessionRepository.observeActiveSession()

        // While running, a session's stored elapsed time is only settled up to its last event; the
        // open interval's length depends on when you ask, so a periodic re-read is what keeps the
        // display honest. Paused/idle need no ticker at all — nothing changes between refreshes.
        private val ticker: Flow<Unit> =
            flow {
                while (true) {
                    delay(1.seconds)
                    emit(Unit)
                }
            }

        private val timerState: Flow<TimerState> =
            activeSession
                .map { it?.status }
                .distinctUntilChanged()
                .flatMapLatest { status ->
                    val pulses = if (status == SessionStatus.RUNNING) merge(refreshes, ticker) else refreshes
                    pulses.onStart { emit(Unit) }.map { sessionRepository.activeState() }
                }

        private val selectedTask: Flow<StudyTask?> =
            combine(draftTaskId, tasks) { taskId, tasks -> tasks.firstOrNull { it.id == taskId } }

        private val suggestedTask: Flow<StudyTask?> =
            tasks
                .map { tasks ->
                    tasks
                        .filterNot { it.isCompleted }
                        .mapNotNull { task -> task.dueAtUtc?.let { dueAt -> dueAt to task } }
                        .minByOrNull { it.first }
                        ?.second
                }.distinctUntilChanged()

        private val draft: Flow<TimerDraft> =
            combine(
                selectedTask,
                suggestedTask,
                draftSubjectId,
                draftNote,
            ) { selectedTask, suggestedTask, subjectId, note ->
                TimerDraft(
                    subjectId = subjectId,
                    note = note,
                    selectedTask = selectedTask,
                    suggestedTask = suggestedTask,
                )
            }

        public val state: StateFlow<TimerUiState> =
            combine(
                timerState,
                activeSession,
                subjectRepository.observeSubjects(),
                draft,
                keepScreenOn,
            ) { timerState, session, subjects, draft, keepScreenOn ->
                buildState(
                    timerState = timerState,
                    session = session,
                    subjects = subjects,
                    draft = draft,
                    keepScreenOn = keepScreenOn,
                )
            }.stateInViewModel(TimerUiState())

        init {
            // Runs once per process start: closes any interval a reboot left open, booking the gap
            // honestly as unverified rather than silently counting or discarding it.
            viewModelScope.launch {
                sessionRepository.reconcile(UUID.randomUUID().toString(), anchoredClock.anchor())
                refreshes.tryEmit(Unit)
            }
        }

        override fun onEvent(event: TimerUiEvent) {
            when (event) {
                TimerUiEvent.Refresh -> {
                    refreshes.tryEmit(Unit)
                }

                TimerUiEvent.StartRequested -> {
                    start()
                }

                is TimerUiEvent.StudyNowRequested -> {
                    studyNow(event.taskId, event.subjectId)
                }

                is TimerUiEvent.RouteStudyNowRequested -> {
                    if (!routeStudyNowHandled(event.taskId, event.subjectId)) {
                        markRouteStudyNowHandled(event.taskId, event.subjectId)
                        studyNow(event.taskId, event.subjectId)
                    }
                }

                TimerUiEvent.PauseRequested -> {
                    execute(TimerCommand.Pause)
                }

                TimerUiEvent.ResumeRequested -> {
                    execute(TimerCommand.Resume)
                }

                TimerUiEvent.StopRequested -> {
                    execute(TimerCommand.Stop)
                }

                is TimerUiEvent.SubjectSelected -> {
                    draftSubjectId.value = event.subjectId
                }

                is TimerUiEvent.NoteChanged -> {
                    draftNote.value = event.note
                }

                is TimerUiEvent.KeepScreenOnChanged -> {
                    keepScreenOn.value = event.keepScreenOn
                }
            }
        }

        private fun start() {
            execute(
                TimerCommand.Start(
                    sessionId = UUID.randomUUID().toString(),
                    taskId = draftTaskId.value,
                    subjectId = draftSubjectId.value,
                    note = draftNote.value.trim().ifBlank { null },
                ),
            )
        }

        private fun studyNow(
            taskId: String,
            subjectId: String?,
        ) {
            draftTaskId.value = taskId
            draftSubjectId.value = subjectId
            start()
        }

        private fun routeStudyNowHandled(
            taskId: String,
            subjectId: String?,
        ): Boolean =
            savedStateHandle.get<Boolean>(ROUTE_STUDY_NOW_HANDLED_KEY) == true &&
                savedStateHandle.get<String>(ROUTE_TASK_ID_KEY) == taskId &&
                savedStateHandle.get<String>(ROUTE_SUBJECT_ID_KEY) == subjectId

        private fun markRouteStudyNowHandled(
            taskId: String,
            subjectId: String?,
        ) {
            savedStateHandle[ROUTE_STUDY_NOW_HANDLED_KEY] = true
            savedStateHandle[ROUTE_TASK_ID_KEY] = taskId
            savedStateHandle[ROUTE_SUBJECT_ID_KEY] = subjectId
        }

        private fun execute(command: TimerCommand) {
            viewModelScope.launch {
                val result =
                    sessionRepository.execute(
                        command = command,
                        eventId = UUID.randomUUID().toString(),
                        anchor = anchoredClock.anchor(),
                    )
                if (result is SessionCommandResult.Rejected) {
                    emitEffect(TimerUiEffect.CommandRejected(result.reason))
                    return@launch
                }
                if (command is TimerCommand.Start) {
                    draftSubjectId.value = null
                    draftTaskId.value = null
                    draftNote.value = ""
                }
                refreshes.tryEmit(Unit)
            }
        }

        private fun buildState(
            timerState: TimerState,
            session: StudySession?,
            subjects: List<Subject>,
            draft: TimerDraft,
            keepScreenOn: Boolean,
        ): TimerUiState {
            val elapsed = TimerEngine.elapsedAt(timerState, anchoredClock.anchor())
            return TimerUiState(
                phase = timerState.toPhase(),
                elapsedSeconds = elapsed.counted.inWholeSeconds,
                hasUnverifiedTime = elapsed.hasUnverifiedTime,
                subjects = subjects,
                selectedTask = draft.selectedTask.takeIf { session == null },
                suggestedTask = draft.suggestedTask,
                selectedSubjectId = session?.subjectId ?: draft.subjectId,
                note = session?.note ?: draft.note,
                keepScreenOn = keepScreenOn,
            )
        }

        private companion object {
            const val ROUTE_STUDY_NOW_HANDLED_KEY = "timer.routeStudyNowHandled"
            const val ROUTE_TASK_ID_KEY = "timer.routeTaskId"
            const val ROUTE_SUBJECT_ID_KEY = "timer.routeSubjectId"

            fun TimerState.toPhase(): TimerPhase =
                when (this) {
                    TimerState.Idle, is TimerState.Stopped -> TimerPhase.IDLE
                    is TimerState.Running -> TimerPhase.RUNNING
                    is TimerState.Paused -> TimerPhase.PAUSED
                }

            private data class TimerDraft(
                val subjectId: String?,
                val note: String,
                val selectedTask: StudyTask?,
                val suggestedTask: StudyTask?,
            )
        }
    }
