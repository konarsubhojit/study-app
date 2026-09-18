package dev.studyflow.core.model

/**
 * The append-only log entry that a study session is made of.
 *
 * Sessions are *event sourced*: nothing ever mutates a "current elapsed" column. The app appends a
 * row, and every reader derives state by folding the log. That choice is what makes the timer
 * survive process death for free — there is no in-memory counter to lose — and it makes reboot and
 * clock-change handling auditable rather than guessy.
 *
 * @property id stable identifier, assigned by the writer so that inserts are idempotent on retry.
 * @property sessionId the session this event belongs to.
 * @property type what happened.
 * @property anchor when it happened, on both clocks (see [TimeAnchor]).
 * @property sequence monotonically increasing position within the session. Ordering never depends
 *   on a timestamp, because timestamps are exactly the thing that can move.
 */
public data class SessionEvent(
    val id: String,
    val sessionId: String,
    val type: SessionEventType,
    val anchor: TimeAnchor,
    val sequence: Long,
) {
    init {
        require(id.isNotBlank()) { "SessionEvent.id must not be blank" }
        require(sessionId.isNotBlank()) { "SessionEvent.sessionId must not be blank" }
        require(sequence >= 0) { "SessionEvent.sequence must not be negative, was $sequence" }
    }
}

/** The transitions a study session can record. */
public enum class SessionEventType {
    /** Opens the session and starts the first counted interval. */
    STARTED,

    /** Closes the current counted interval. */
    PAUSED,

    /** Opens a new counted interval after a pause. */
    RESUMED,

    /** Records that a focus interval ended and a non-counted break began. */
    BREAK_STARTED,

    /** Records that the user returned from a break and opened another counted interval. */
    FOCUS_RESUMED,

    /** Records the user confirmed they are still studying without changing the counted interval. */
    ACTIVITY_CONFIRMED,

    /** Closes the session permanently. */
    STOPPED,
}
