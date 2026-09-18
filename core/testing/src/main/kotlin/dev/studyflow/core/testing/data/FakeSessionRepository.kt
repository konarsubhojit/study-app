package dev.studyflow.core.testing.data

import dev.studyflow.core.domain.session.SessionCommandResult
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.model.TimeAnchor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration

/**
 * In-memory [SessionRepository] that records every command rather than replaying real timer
 * rules — enough for a caller test that only needs to assert *which* command was issued, such as
 * a reminder notification's "Start study session" action.
 */
public class FakeSessionRepository : SessionRepository {
    public val executedCommands: MutableList<TimerCommand> = mutableListOf()
    private val sessions = MutableStateFlow<List<StudySession>>(emptyList())

    override fun observeActiveSession(): Flow<StudySession?> = sessions.map { list -> list.firstOrNull(StudySession::isActive) }

    override fun observeSession(sessionId: String): Flow<StudySession?> = sessions.map { list -> list.firstOrNull { it.id == sessionId } }

    override fun observeSessions(): Flow<List<StudySession>> = sessions

    override suspend fun activeState(): TimerState = TimerState.Idle

    public fun setSessions(values: List<StudySession>) {
        sessions.value = values
    }

    override suspend fun execute(
        command: TimerCommand,
        eventId: String,
        anchor: TimeAnchor,
    ): SessionCommandResult {
        executedCommands += command
        val session =
            when (command) {
                is TimerCommand.Start -> {
                    StudySession(
                        id = command.sessionId,
                        taskId = command.taskId,
                        subjectId = command.subjectId,
                        note = command.note,
                        startedAt = anchor.wallClock,
                        endedAt = null,
                        status = SessionStatus.RUNNING,
                        elapsed = SessionElapsed(counted = Duration.ZERO, unverified = Duration.ZERO),
                        deviceId = "fake-device",
                        updatedAt = anchor.wallClock,
                    )
                }

                else -> {
                    sessions.value.firstOrNull(StudySession::isActive)
                }
            } ?: return SessionCommandResult.Unchanged(TimerState.Idle)
        sessions.value = sessions.value.filterNot { it.id == session.id } + session
        return SessionCommandResult.Applied(
            session = session,
            state = TimerState.Running(session.id, Duration.ZERO, Duration.ZERO, lastSequence = 0, openedAt = anchor),
        )
    }

    override suspend fun reconcile(
        eventId: String,
        now: TimeAnchor,
    ): SessionCommandResult = SessionCommandResult.Unchanged(TimerState.Idle)
}
