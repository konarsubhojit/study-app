package dev.studyflow.core.database.session

import dev.studyflow.core.database.dao.SessionDao
import dev.studyflow.core.database.entity.SessionWithEvents
import dev.studyflow.core.database.entity.asDescriptor
import dev.studyflow.core.database.entity.asEntity
import dev.studyflow.core.database.entity.asExternalModel
import dev.studyflow.core.domain.result.toDomainError
import dev.studyflow.core.domain.session.SessionCommandResult
import dev.studyflow.core.domain.session.SessionDescriptor
import dev.studyflow.core.domain.session.SessionReducer
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.domain.timer.TimerCommandResult
import dev.studyflow.core.domain.timer.TimerEngine
import dev.studyflow.core.domain.timer.TimerReconciliation
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.model.TimeAnchor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * The Room-backed [SessionRepository].
 *
 * Every command follows the same three steps: fold the stored log into state, ask [TimerEngine]
 * whether the command is legal, and — if it is — commit the resulting event together with the
 * projection it implies, in one transaction. Nothing is cached in memory between calls, so the
 * answer after a process death is identical to the answer before it.
 *
 * @param deviceId identifies this device. Sessions are scoped to it because an `elapsedRealtime`
 *   anchor from another device is meaningless here, and it is the scope "at most one active
 *   session" is counted within.
 */
public class OfflineFirstSessionRepository(
    private val dao: SessionDao,
    private val deviceId: String,
    private val maximumRunningDuration: Duration = TimerEngine.DEFAULT_MAXIMUM_RUNNING_DURATION,
) : SessionRepository {
    /**
     * Serialises command evaluation within the process.
     *
     * Reading the log and appending to it is a read-modify-write, and two taps arriving together
     * would otherwise both observe "paused" and both append a `RESUMED`. The database transaction
     * re-checks the same facts, so correctness does not *depend* on this lock; the lock is what
     * makes the common case a clean rejection rather than a rolled-back write.
     */
    private val commandLock = Mutex()

    init {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
    }

    /**
     * Deliberately takes the first row rather than asserting there is only one: this flow feeds the
     * UI, and a corrupted database should not turn every screen into a crash. Writes are where the
     * invariant is enforced, and the command path's active-session lookup is where a violation is
     * surfaced.
     */
    override fun observeActiveSession(): Flow<StudySession?> =
        dao.observeActive(deviceId).map { active -> active.firstOrNull()?.asExternalModel() }

    override fun observeSession(sessionId: String): Flow<StudySession?> =
        dao.observeSession(sessionId).map { it?.asExternalModel() }

    override suspend fun activeState(): TimerState = activeSession()?.foldEvents() ?: TimerState.Idle

    override suspend fun execute(
        command: TimerCommand,
        eventId: String,
        anchor: TimeAnchor,
    ): SessionCommandResult =
        commandLock.withLock {
            runCatchingStorage {
                val active = activeSession()
                appliedDuplicate(eventId, active)?.let { return@runCatchingStorage it }
                val state = active?.foldEvents() ?: TimerState.Idle

                when (val outcome = TimerEngine.execute(state, command, eventId, anchor)) {
                    is TimerCommandResult.Rejected -> {
                        SessionCommandResult.Rejected(outcome.reason)
                    }

                    is TimerCommandResult.Accepted -> {
                        commit(
                            descriptor = command.descriptorFor(active),
                            events = active.eventsPlus(outcome.event),
                            event = outcome.event,
                        )
                    }
                }
            }
        }

    override suspend fun reconcile(
        eventId: String,
        now: TimeAnchor,
    ): SessionCommandResult =
        commandLock.withLock {
            runCatchingStorage {
                val active =
                    activeSession() ?: return@runCatchingStorage SessionCommandResult.Unchanged(TimerState.Idle)
                appliedDuplicate(eventId, active)?.let { return@runCatchingStorage it }
                when (
                    val outcome =
                        TimerEngine.reconcile(
                            state = active.foldEvents(),
                            eventId = eventId,
                            now = now,
                            maximumRunningDuration = maximumRunningDuration,
                        )
                ) {
                    is TimerReconciliation.Unchanged -> {
                        SessionCommandResult.Unchanged(outcome.state)
                    }

                    is TimerReconciliation.Adjustment -> {
                        commit(
                            descriptor = active.session.asDescriptor(),
                            events = active.eventsPlus(outcome.event),
                            event = outcome.event,
                        )
                    }
                }
            }
        }

    /** Writes [event] and the projection its log implies, atomically. */
    private suspend fun commit(
        descriptor: SessionDescriptor,
        events: List<SessionEvent>,
        event: SessionEvent,
    ): SessionCommandResult {
        val session =
            requireNotNull(SessionReducer.reduce(descriptor, events)) {
                "a committed event always projects to a session"
            }
        dao.appendAndProject(session.asEntity(), event.asEntity())
        return SessionCommandResult.Applied(session, TimerEngine.fold(events))
    }

    /**
     * A command retry with the same event id is a read of the original outcome, not a second write.
     * If a crash left only the event durable, this also repairs the projection before returning.
     */
    private suspend fun appliedDuplicate(
        eventId: String,
        stored: SessionWithEvents?,
    ): SessionCommandResult.Applied? {
        if (stored == null || stored.events.none { it.id == eventId }) return null
        val events = stored.events.map { it.asExternalModel() }
        val session =
            requireNotNull(SessionReducer.reduce(stored.session.asDescriptor(), events)) {
                "event $eventId belongs to an empty session log"
            }
        dao.upsertSessions(listOf(session.asEntity()))
        return SessionCommandResult.Applied(session, TimerEngine.fold(events))
    }

    /**
     * The running or paused session on this device, or `null`.
     *
     * More than one would mean the invariant had been violated, which is a bug worth surfacing
     * rather than a row worth picking arbitrarily.
     */
    private suspend fun activeSession(): SessionWithEvents? {
        val active = dao.active(deviceId)
        check(active.size <= 1) {
            "device $deviceId has ${active.size} active sessions: ${active.map { it.session.id }}"
        }
        return active.firstOrNull()
    }

    private fun SessionWithEvents.foldEvents(): TimerState = TimerEngine.fold(events.map { it.asExternalModel() })

    private fun SessionWithEvents?.eventsPlus(appended: SessionEvent): List<SessionEvent> =
        this?.events?.map { it.asExternalModel() }.orEmpty() + appended

    /**
     * Metadata for the session a command applies to.
     *
     * `Start` brings its own subject and note; every other command continues the session that
     * already exists and must not rewrite its metadata.
     */
    private fun TimerCommand.descriptorFor(active: SessionWithEvents?): SessionDescriptor =
        when (this) {
            is TimerCommand.Start -> {
                SessionDescriptor(id = sessionId, deviceId = deviceId, subjectId = subjectId, note = note)
            }

            else -> {
                requireNotNull(active?.session?.asDescriptor()) {
                    "$this has no session to apply to"
                }
            }
        }

    private inline fun runCatchingStorage(block: () -> SessionCommandResult): SessionCommandResult =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Throwable,
        ) {
            SessionCommandResult.Failed(failure.toDomainError())
        }
}
