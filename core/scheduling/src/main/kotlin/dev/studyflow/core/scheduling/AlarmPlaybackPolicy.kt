package dev.studyflow.core.scheduling

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The escalating-volume and timeout rules an alarm-style reminder's playback follows (issue #48).
 *
 * Kept as pure functions of an elapsed [Duration] — no `AudioManager`, no `Vibrator`, no clock read
 * of its own — so [AlarmPlaybackService] is a thin, hard-to-get-wrong shell around a policy that is
 * fully unit-testable without Robolectric.
 */
public object AlarmPlaybackPolicy {
    /** How long it takes the volume to ramp from [MIN_VOLUME_FRACTION] to full. */
    public val ESCALATION_DURATION: Duration = 20.seconds

    /**
     * How long an unattended alarm rings before it stops itself.
     *
     * Two minutes is long enough for a groggy user to reach for the phone, short enough that a
     * missed alarm does not drain the battery or hold audio focus indefinitely. What happens next
     * is [outcomeAfterTimeout]: it is *not* a silent dismiss.
     */
    public val TIMEOUT_DURATION: Duration = 2.minutes

    /** Never starts silent — the first ring must already be audible. */
    private const val MIN_VOLUME_FRACTION = 0.15f
    private const val MAX_VOLUME_FRACTION = 1f

    /** A short double-pulse, repeated, distinct from a plain notification buzz. */
    public val VIBRATION_PATTERN: LongArray = longArrayOf(0, 400, 200, 400, 800)

    /** Repeat the whole [VIBRATION_PATTERN] from its start (index 0) until told to stop. */
    public const val VIBRATION_REPEAT_INDEX: Int = 0

    /** The player's volume (both channels) at [elapsed] time since the alarm started ringing. */
    public fun volumeAt(elapsed: Duration): Float {
        if (elapsed >= ESCALATION_DURATION) return MAX_VOLUME_FRACTION
        val fraction =
            (elapsed.inWholeMilliseconds.toFloat() / ESCALATION_DURATION.inWholeMilliseconds)
                .coerceIn(0f, 1f)
        return MIN_VOLUME_FRACTION + (MAX_VOLUME_FRACTION - MIN_VOLUME_FRACTION) * fraction
    }

    /** True once [elapsed] has reached [TIMEOUT_DURATION] and playback should stop itself. */
    public fun hasTimedOut(elapsed: Duration): Boolean = elapsed >= TIMEOUT_DURATION

    /** How often the volume ramp and the timeout check are re-evaluated. */
    public val TICK_INTERVAL: Duration = 500.milliseconds

    /**
     * What an alarm that nobody attended to should do once [TIMEOUT_DURATION] elapses.
     *
     * Auto-snoozing rather than silently dismissing: an alarm nobody heard is exactly the case a
     * second chance matters most for, and [ReminderActionExecutor]'s [MAX_SNOOZE_COUNT] still stops
     * this from repeating forever — a timeout is dispatched through the same Snooze action as the
     * button, so the cap applies identically whether the alarm was silenced by a tap or by time.
     */
    public val OUTCOME_AFTER_TIMEOUT: ReminderActionKind = ReminderActionKind.SNOOZE
}
