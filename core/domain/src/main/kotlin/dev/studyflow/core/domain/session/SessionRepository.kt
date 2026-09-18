package dev.studyflow.core.domain.session

import dev.studyflow.core.domain.result.DomainError
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.domain.timer.TimerRejection
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.model.TimeAnchor
import kotlinx.coroutines.flow.Flow

/**
 * Durable storage for study sessions, as an append-only event log plus its projection.
 *
 * Implementations must commit the event and the projection it implies in a *single* transaction.
 * That is what makes process death a non-event: either both landed, or neither did, and in both
 * cases replaying the log with [SessionReducer] yields the same answer.
 */
public interface SessionRepository {
    /**
     * The session that is currently running or paused on this device, or `null` when there is none.
     *
     * At most one such session can exist — that invariant is enforced on write — so the UI can bind
     * to this directly instead of guessing which of several rows to show.
     */
    public fun observeActiveSession(): Flow<StudySession?>

    /** One session by id, re-derived whenever its log changes; `null` while it does not exist. */
    public fun observeSession(sessionId: String): Flow<StudySession?>

    /** All non-deleted local projections, for compact local summaries such as the home dashboard. */
    public fun observeSessions(): Flow<List<StudySession>>

    /** The timer state of the active session, folded from its log; [TimerState.Idle] when none. */
    public suspend fun activeState(): TimerState

    /**
     * Validates [command] against the stored log and, if legal, appends the event it produces.
     *
     * @param eventId caller-supplied identifier for the event. It is the log's primary key, so a
     *   retry that reuses it is refused rather than double-counted; callers that want to know the
     *   outcome of the original write read it back instead of retrying blind.
     * @param anchor both clocks, read as close as possible to the moment the user acted.
     */
    public suspend fun execute(
        command: TimerCommand,
        eventId: String,
        anchor: TimeAnchor,
    ): SessionCommandResult

    /**
     * Closes an interval that was orphaned by a reboot, booking the gap as unverified time.
     *
     * Run on process start and on `BOOT_COMPLETED`; a no-op when nothing was running or when the
     * open interval belongs to the current boot.
     */
    public suspend fun reconcile(
        eventId: String,
        now: TimeAnchor,
    ): SessionCommandResult
}

/**
 * Process-local side effects that follow a successfully committed timer command.
 *
 * Repository implementations must notify observers only after the event and projection have been
 * durably written. Observers are not part of the commit path: a notification/service update must not
 * turn an already-persisted timer action into a failed domain command.
 */
public fun interface SessionCommandObserver {
    public fun onSessionCommandApplied(result: SessionCommandResult.Applied)
}

/** Outcome of a persisted session command. */
public sealed interface SessionCommandResult {
    /**
     * The command was legal and durably recorded.
     *
     * @property session the projection as it now stands.
     * @property state the folded timer state, which the caller can keep instead of re-reading.
     */
    public data class Applied(
        val session: StudySession,
        val state: TimerState,
    ) : SessionCommandResult

    /**
     * The command made no sense in the stored state, so nothing was written.
     *
     * Rejections are ordinary control flow — a double tap on "pause" is not an error worth a
     * crash report — which is why they are modelled separately from [Failed].
     */
    public data class Rejected(
        val reason: TimerRejection,
    ) : SessionCommandResult

    /** Storage itself failed; whether anything was written is decided by the transaction. */
    public data class Failed(
        val error: DomainError,
    ) : SessionCommandResult

    /** The command was legal but required no event, e.g. reconciling a session that never ran. */
    public data class Unchanged(
        val state: TimerState,
    ) : SessionCommandResult
}
