package dev.studyflow.core.common.time

import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.TimeAnchor
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The adjustable, human-meaningful clock — `System.currentTimeMillis()` on Android.
 *
 * Correct for "when did this happen", wrong for "how long did this take": it moves whenever NTP
 * syncs, the user edits the date, or the carrier pushes a time update.
 */
public fun interface Clock {
    public fun now(): Instant
}

/** Compatibility name for [Clock]. */
public typealias WallClock = Clock

/** Reads the device wall clock. */
public object SystemWallClock : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
}

/**
 * The monotonic clock — `SystemClock.elapsedRealtime()` on Android.
 *
 * Counts time since boot *including deep sleep*, never runs backwards, and cannot be adjusted.
 * Correct for "how long did this take", useless for "when", and resets to zero on every reboot —
 * hence [BootIdProvider].
 *
 * Note the deliberate choice of `elapsedRealtime` over `uptimeMillis`: the latter stops during deep
 * sleep, which would silently under-count every study session on a device left alone for an hour.
 */
public fun interface ElapsedRealtimeSource {
    public fun uptime(): Duration
}

/** Compatibility name for [ElapsedRealtimeSource]. */
public typealias UptimeClock = ElapsedRealtimeSource

/** Supplies an identifier that changes on every reboot, scoping [UptimeClock] readings to a boot. */
public fun interface BootIdProvider {
    public fun current(): BootId
}

/**
 * Supplies the device's current time zone.
 *
 * Injected rather than read statically so that DST and travel scenarios can be exercised in plain
 * JVM tests instead of only being discovered by users in late October.
 */
public fun interface TimeZoneProvider {
    public fun current(): TimeZone
}

/** Reads the device's current time zone. */
public object SystemTimeZoneProvider : TimeZoneProvider {
    override fun current(): TimeZone = TimeZone.currentSystemDefault()
}

/**
 * Captures both clocks at once, which is the only reading the timer logic ever takes.
 *
 * Implementations should read the two clocks back to back. They are not sampled atomically, but the
 * gap is sub-millisecond and constant, whereas the failure this design guards against — a clock
 * adjustment of minutes or hours — is many orders of magnitude larger.
 */
public interface AnchoredClock : WallClock {
    /** Reads wall clock, monotonic clock and boot id as a single [TimeAnchor]. */
    public fun anchor(): TimeAnchor

    override fun now(): Instant = anchor().wallClock
}

/** Builds a [TimeAnchor] from the three independently injectable sources. */
public class DefaultAnchoredClock(
    private val wallClock: WallClock,
    private val uptimeClock: UptimeClock,
    private val bootIdProvider: BootIdProvider,
) : AnchoredClock {
    override fun anchor(): TimeAnchor =
        TimeAnchor(
            uptime = uptimeClock.uptime(),
            wallClock = wallClock.now(),
            bootId = bootIdProvider.current(),
        )
}
