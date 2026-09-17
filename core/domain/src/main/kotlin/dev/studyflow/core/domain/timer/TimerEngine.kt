package dev.studyflow.core.domain.timer

import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.SessionEventType
import dev.studyflow.core.model.TimeAnchor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * The stopwatch, as a pure function.
 *
 * ### Why it survives app closure
 *
 * Nothing here counts. There is no tick loop, no `CountDownTimer`, no wake lock, no accumulating
 * field — those are all ways of *remembering* elapsed time, and memory is exactly what the process
 * loses when Android kills it. Instead every state change appends one row, and elapsed time is
 * recomputed from the log whenever somebody asks. Kill the process, reboot the phone, restore from
 * a backup: replay the log and the answer is identical. The ticking digits the user sees are
 * rendered by the system's own notification chronometer from a single anchor, so the app does not
 * need to be alive to keep the display honest.
 *
 * ### Why every event carries two clocks
 *
 * Measuring with the wall clock alone means an NTP correction or a user fiddling with the date can
 * add or erase hours of "study time". Measuring with the monotonic clock alone means a reboot
 * silently resets the origin and the session appears to have started in the future. Recording both,
 * plus the boot id, lets the engine tell the two apart: within a boot the monotonic delta is
 * authoritative and clock changes are ignored; across a boot the monotonic clocks are incomparable
 * and the engine refuses to guess — see [SessionElapsed.unverified].
 */
public object TimerEngine {
    /**
     * Recovery auto-pauses an uninterrupted same-boot interval after this cap.
     *
     * A full day is deliberately generous for normal study while bounding sessions left running
     * after a service crash, swipe-away, or forgotten timer.
     */
    public val DEFAULT_MAXIMUM_RUNNING_DURATION: Duration = 24.hours

    /**
     * Rebuilds state by replaying a session's event log.
     *
     * Events are ordered by [SessionEvent.sequence] rather than by any timestamp, because the
     * timestamps are precisely the values that can move. Malformed logs (a `RESUMED` with an
     * interval already open, a `PAUSED` with none) are tolerated rather than thrown on: a log that
     * somehow got corrupted should still yield the user's best available time, not a crash loop.
     *
     * @param events all events for a single session, in any order.
     * @throws IllegalArgumentException if [events] mixes multiple sessions.
     */
    public fun fold(events: List<SessionEvent>): TimerState {
        if (events.isEmpty()) return TimerState.Idle

        val ordered = events.sortedBy { it.sequence }
        val sessionId = ordered.first().sessionId
        require(ordered.all { it.sessionId == sessionId }) {
            "fold() expects the log of a single session, found ${ordered.map { it.sessionId }.distinct()}"
        }

        var settled = Duration.ZERO
        var unverified = Duration.ZERO
        var openedAt: TimeAnchor? = null
        var stopped = false

        for (event in ordered) {
            when (event.type) {
                SessionEventType.STARTED, SessionEventType.RESUMED -> {
                    if (openedAt == null && !stopped) openedAt = event.anchor
                }

                SessionEventType.PAUSED, SessionEventType.STOPPED -> {
                    openedAt?.let { open ->
                        val interval = measure(open, event.anchor)
                        settled += interval.counted
                        unverified += interval.unverified
                    }
                    openedAt = null
                    if (event.type == SessionEventType.STOPPED) stopped = true
                }
            }
        }

        val lastSequence = ordered.last().sequence
        return when {
            stopped -> TimerState.Stopped(sessionId, settled, unverified, lastSequence)
            openedAt != null -> TimerState.Running(sessionId, settled, unverified, lastSequence, openedAt)
            else -> TimerState.Paused(sessionId, settled, unverified, lastSequence)
        }
    }

    /**
     * Elapsed time as of [now], including the currently open interval.
     *
     * This is the read-time computation the UI calls. It is cheap, allocation-light and idempotent,
     * so a Compose recomposition, a notification rebuild and a widget update can all call it
     * independently and always agree.
     */
    public fun elapsedAt(
        state: TimerState,
        now: TimeAnchor,
    ): SessionElapsed =
        when (state) {
            TimerState.Idle -> {
                SessionElapsed.ZERO
            }

            is TimerState.Running -> {
                val open = measure(state.openedAt, now)
                SessionElapsed(
                    counted = state.settled + open.counted,
                    unverified = state.unverified + open.unverified,
                )
            }

            is TimerState.Paused -> {
                SessionElapsed(counted = state.settled, unverified = state.unverified)
            }

            is TimerState.Stopped -> {
                SessionElapsed(counted = state.settled, unverified = state.unverified)
            }
        }

    /**
     * Validates [command] against [state] and produces the event to append.
     *
     * The engine never writes anything itself; it returns the event and lets the caller persist it.
     * That keeps the whole state machine testable without a database and makes the durability
     * boundary explicit: the log is the commit point.
     *
     * @param eventId caller-supplied identifier, so a retried write is idempotent.
     * @param anchor both clocks, read as close as possible to the moment the user acted.
     */
    public fun execute(
        state: TimerState,
        command: TimerCommand,
        eventId: String,
        anchor: TimeAnchor,
    ): TimerCommandResult {
        rejectionFor(state, command)?.let { return TimerCommandResult.Rejected(it) }

        return when (command) {
            is TimerCommand.Start -> {
                accept(
                    event =
                        SessionEvent(
                            id = eventId,
                            sessionId = command.sessionId,
                            type = SessionEventType.STARTED,
                            anchor = anchor,
                            sequence = 0,
                        ),
                    previous = emptyList(),
                )
            }

            TimerCommand.Pause -> {
                appendTo(state, SessionEventType.PAUSED, eventId, anchor)
            }

            TimerCommand.Resume -> {
                appendTo(state, SessionEventType.RESUMED, eventId, anchor)
            }

            TimerCommand.Stop -> {
                appendTo(state, SessionEventType.STOPPED, eventId, anchor)
            }
        }
    }

    /**
     * Detects a reboot that happened while the timer was running.
     *
     * Run this on process start and on `BOOT_COMPLETED`. When the open interval was anchored in a
     * previous boot the engine cannot know how much of the wall-clock gap was studying and how much
     * was the phone being switched off, so it closes the interval and books the whole gap as
     * [SessionElapsed.unverified] for the user to confirm, reject or edit.
     *
     * Silently counting the gap would invent study time; silently discarding it would erase real
     * work. Asking is the only honest option, and it only happens in the genuinely ambiguous case.
     *
     * @param eventId identifier for the `PAUSED` event this may produce.
     * @param now both clocks, read in the *current* boot.
     */
    public fun reconcile(
        state: TimerState,
        eventId: String,
        now: TimeAnchor,
        maximumRunningDuration: Duration = DEFAULT_MAXIMUM_RUNNING_DURATION,
    ): TimerReconciliation {
        require(maximumRunningDuration.isPositive()) { "maximumRunningDuration must be positive" }
        return when {
            state !is TimerState.Running -> {
                TimerReconciliation.Unchanged(state)
            }

            state.openedAt.isSameBootAs(now) -> {
                reconcileSameBoot(state, eventId, now, maximumRunningDuration)
            }

            else -> {
                val gap = state.openedAt.wallClockDurationTo(now).coerceAtLeast(Duration.ZERO)
                val event = pauseEvent(state, eventId, now)
                TimerReconciliation.RebootGap(
                    event = event,
                    state = pausedState(state, event, Duration.ZERO, gap),
                    unverifiedGap = gap,
                )
            }
        }
    }

    /**
     * How far the wall clock drifted relative to the monotonic clock during the open interval.
     *
     * Purely diagnostic — it never changes a measurement. It exists so the app can tell the user
     * "your device's clock changed by 45 minutes during this session; your timer is unaffected"
     * instead of leaving them to wonder why the numbers look odd.
     *
     * @return the skew, or `null` when the timer is not running or the anchors span a reboot.
     */
    public fun wallClockSkew(
        state: TimerState,
        now: TimeAnchor,
    ): Duration? = (state as? TimerState.Running)?.openedAt?.wallClockSkewTo(now)

    private fun rejectionFor(
        state: TimerState,
        command: TimerCommand,
    ): TimerRejection? =
        when (command) {
            is TimerCommand.Start -> {
                when (state) {
                    TimerState.Idle, is TimerState.Stopped -> null
                    is TimerState.Running, is TimerState.Paused -> TimerRejection.SESSION_ALREADY_ACTIVE
                }
            }

            TimerCommand.Pause -> {
                requireActive(state) ?: (TimerRejection.NOT_RUNNING.takeIf { state !is TimerState.Running })
            }

            TimerCommand.Resume -> {
                requireActive(state) ?: (TimerRejection.NOT_PAUSED.takeIf { state !is TimerState.Paused })
            }

            TimerCommand.Stop -> {
                requireActive(state)
            }
        }

    private fun requireActive(state: TimerState): TimerRejection? =
        when (state) {
            TimerState.Idle -> TimerRejection.NO_ACTIVE_SESSION
            is TimerState.Stopped -> TimerRejection.SESSION_ALREADY_STOPPED
            is TimerState.Running, is TimerState.Paused -> null
        }

    private fun appendTo(
        state: TimerState,
        type: SessionEventType,
        eventId: String,
        anchor: TimeAnchor,
    ): TimerCommandResult {
        val active = state as TimerState.Active
        val event =
            SessionEvent(
                id = eventId,
                sessionId = active.sessionId,
                type = type,
                anchor = anchor,
                sequence = active.lastSequence + 1,
            )
        val next =
            when (type) {
                SessionEventType.RESUMED -> {
                    TimerState.Running(
                        sessionId = active.sessionId,
                        settled = active.settled,
                        unverified = active.unverified,
                        lastSequence = event.sequence,
                        openedAt = anchor,
                    )
                }

                else -> {
                    val open = (state as? TimerState.Running)?.let { measure(it.openedAt, anchor) } ?: Interval.NONE
                    val settled = active.settled + open.counted
                    val unverified = active.unverified + open.unverified
                    if (type == SessionEventType.STOPPED) {
                        TimerState.Stopped(active.sessionId, settled, unverified, event.sequence)
                    } else {
                        TimerState.Paused(active.sessionId, settled, unverified, event.sequence)
                    }
                }
            }
        return TimerCommandResult.Accepted(event, next)
    }

    private fun accept(
        event: SessionEvent,
        previous: List<SessionEvent>,
    ): TimerCommandResult = TimerCommandResult.Accepted(event, fold(previous + event) as TimerState.Active)

    /**
     * Measures one interval between two anchors.
     *
     * This single rule is what makes the whole design work, and it is applied identically to closed
     * intervals during a fold and to the open interval at read time:
     *
     * - same boot: trust the monotonic delta and ignore the wall clock entirely;
     * - different boot: the monotonic clocks are incomparable, so record the wall-clock gap as
     *   unverified and count nothing.
     */
    private fun measure(
        from: TimeAnchor,
        to: TimeAnchor,
    ): Interval {
        val monotonic = from.uptimeDurationTo(to)
        return if (monotonic != null) {
            Interval(counted = monotonic.coerceAtLeast(Duration.ZERO), unverified = Duration.ZERO)
        } else {
            Interval(
                counted = Duration.ZERO,
                unverified = from.wallClockDurationTo(to).coerceAtLeast(Duration.ZERO),
            )
        }
    }

    private fun pauseEvent(
        state: TimerState.Running,
        eventId: String,
        anchor: TimeAnchor,
    ): SessionEvent =
        SessionEvent(
            id = eventId,
            sessionId = state.sessionId,
            type = SessionEventType.PAUSED,
            anchor = anchor,
            sequence = state.lastSequence + 1,
        )

    private fun reconcileSameBoot(
        state: TimerState.Running,
        eventId: String,
        now: TimeAnchor,
        maximumRunningDuration: Duration,
    ): TimerReconciliation {
        val openDuration = state.openedAt.uptimeDurationTo(now)?.coerceAtLeast(Duration.ZERO)
        return if (openDuration == null || openDuration <= maximumRunningDuration) {
            TimerReconciliation.Unchanged(state)
        } else {
            val event = pauseEvent(state, eventId, state.openedAt + maximumRunningDuration)
            TimerReconciliation.MaximumDurationExceeded(
                event = event,
                state = pausedState(state, event, maximumRunningDuration, Duration.ZERO),
                maximumRunningDuration = maximumRunningDuration,
            )
        }
    }

    private fun pausedState(
        previous: TimerState.Running,
        event: SessionEvent,
        counted: Duration,
        unverified: Duration,
    ): TimerState.Paused =
        TimerState.Paused(
            sessionId = previous.sessionId,
            settled = previous.settled + counted,
            unverified = previous.unverified + unverified,
            lastSequence = event.sequence,
        )

    private operator fun TimeAnchor.plus(duration: Duration): TimeAnchor =
        TimeAnchor(
            uptime = uptime + duration,
            wallClock = wallClock + duration,
            bootId = bootId,
        )

    private data class Interval(
        val counted: Duration,
        val unverified: Duration,
    ) {
        companion object {
            val NONE = Interval(Duration.ZERO, Duration.ZERO)
        }
    }
}

/** Result of [TimerEngine.reconcile]. */
public sealed interface TimerReconciliation {
    /** The state to use going forward. */
    public val state: TimerState

    /** No reboot happened while running; nothing to append. */
    public data class Unchanged(
        override val state: TimerState,
    ) : TimerReconciliation

    /** Reconciliation appended an event and changed the active session projection. */
    public sealed interface Adjustment : TimerReconciliation {
        public val event: SessionEvent

        override val state: TimerState.Paused
    }

    /**
     * A reboot interrupted a running session.
     *
     * @property event the `PAUSED` event that closes the orphaned interval; append it.
     * @property unverifiedGap wall-clock time between the interval opening and now, of which an
     *   unknown portion was the device being switched off. Present it to the user, do not count it.
     */
    public data class RebootGap(
        override val event: SessionEvent,
        override val state: TimerState.Paused,
        val unverifiedGap: Duration,
    ) : Adjustment

    /**
     * A running session exceeded the configured cap during the same boot.
     *
     * @property event the auto-pause event at the configured maximum.
     */
    public data class MaximumDurationExceeded(
        override val event: SessionEvent,
        override val state: TimerState.Paused,
        val maximumRunningDuration: Duration,
    ) : Adjustment
}
