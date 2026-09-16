package dev.studyflow.core.model

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A point in time captured from *both* clocks at once.
 *
 * The whole timer design rests on this pair:
 *
 * - [uptime] is `SystemClock.elapsedRealtime()` — monotonic, counts deep sleep, immune to the user
 *   or the network moving the clock, but resets on reboot and is only comparable within [bootId].
 * - [wallClock] is `System.currentTimeMillis()` — survives reboots and is what a human recognises,
 *   but can jump backwards or forwards at any moment (NTP sync, manual change, timezone-aware
 *   auto-time).
 *
 * Neither is sufficient alone. Recording both, plus the boot they were taken in, is what allows
 * elapsed time to be *derived* rather than accumulated, and allows a reboot gap to be detected
 * instead of silently mis-counted.
 */
public data class TimeAnchor(
    val uptime: Duration,
    val wallClock: Instant,
    val bootId: BootId,
) {
    init {
        require(!uptime.isNegative()) { "uptime anchor must not be negative, was $uptime" }
    }

    /** True when [other] was captured in the same boot, i.e. when [uptime] values are comparable. */
    public fun isSameBootAs(other: TimeAnchor): Boolean = bootId == other.bootId

    /**
     * Monotonic distance from this anchor to [other].
     *
     * @return the elapsed duration, or `null` when the anchors come from different boots and the
     *   monotonic clocks are therefore incomparable.
     */
    public fun uptimeDurationTo(other: TimeAnchor): Duration? = if (isSameBootAs(other)) other.uptime - uptime else null

    /**
     * Wall-clock distance from this anchor to [other].
     *
     * Always available, never trustworthy on its own: the result includes any clock adjustment that
     * happened in between, and cannot distinguish "device switched off" from "device in use".
     */
    public fun wallClockDurationTo(other: TimeAnchor): Duration = other.wallClock - wallClock

    /**
     * How far the wall clock drifted relative to the monotonic clock between the two anchors.
     *
     * Zero in the normal case. A non-zero value means the wall clock was adjusted, which is
     * information worth surfacing but must never change a measured duration.
     *
     * @return the skew, or `null` when the anchors are from different boots.
     */
    public fun wallClockSkewTo(other: TimeAnchor): Duration? =
        uptimeDurationTo(other)?.let { monotonic -> wallClockDurationTo(other) - monotonic }
}
