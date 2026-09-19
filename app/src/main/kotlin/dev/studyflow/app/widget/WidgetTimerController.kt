package dev.studyflow.app.widget

import dev.studyflow.core.common.time.AnchoredClock
import dev.studyflow.core.domain.session.SessionCommandResult
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.domain.timer.TimerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The timer controls behind the home-screen widget and the Quick Settings tile (issue #61).
 *
 * Both entry points write straight to [SessionRepository] rather than asking the foreground
 * service to do it: a tap on a widget or a tile can arrive while the app process is dead, and a
 * background service start is exactly the thing Android may refuse. The event is what matters, and
 * committing it notifies `SessionCommandObserver`s — which is what re-posts the notification,
 * starts the foreground service if the platform allows it, and pushes the new state back out to
 * every widget and the tile.
 */
internal class WidgetTimerController(
    private val sessionRepository: SessionRepository,
    private val clock: AnchoredClock,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    /** What the widget or tile should draw right now, folded from the stored log. */
    suspend fun snapshot(): TimerWidgetSnapshot = sessionRepository.activeState().toWidgetSnapshot(clock.anchor())

    /**
     * The same snapshot, re-derived whenever the active session's projection changes.
     *
     * A widget collects this instead of asking on a timer: the only thing that can change the
     * timer is a committed command, and committing one rewrites the projection this observes.
     */
    fun snapshots(): Flow<TimerWidgetSnapshot> =
        sessionRepository.observeActiveSession().map { session ->
            sessionRepository.activeState().toWidgetSnapshot(clock.anchor(), note = session?.note)
        }

    /** Start, pause or resume, whichever the current state calls for. */
    suspend fun togglePlayback() {
        when (sessionRepository.activeState()) {
            is TimerState.Running -> execute(TimerCommand.Pause)
            is TimerState.Paused -> execute(TimerCommand.Resume)
            TimerState.Idle, is TimerState.Stopped -> start()
        }
    }

    /** The Quick Settings tile's single control: start a session, or finish the one that runs. */
    suspend fun toggleSession() {
        when (sessionRepository.activeState()) {
            is TimerState.Running, is TimerState.Paused -> execute(TimerCommand.Stop)
            TimerState.Idle, is TimerState.Stopped -> start()
        }
    }

    suspend fun stop() {
        execute(TimerCommand.Stop)
    }

    private suspend fun start() {
        execute(TimerCommand.Start(sessionId = idGenerator()))
    }

    private suspend fun execute(command: TimerCommand): SessionCommandResult =
        sessionRepository.execute(
            command = command,
            eventId = idGenerator(),
            anchor = clock.anchor(),
        )
}
