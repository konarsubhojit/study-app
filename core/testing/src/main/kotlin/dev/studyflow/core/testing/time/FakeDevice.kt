package dev.studyflow.core.testing.time

import dev.studyflow.core.common.time.AnchoredClock
import dev.studyflow.core.common.time.BootIdProvider
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.common.time.UptimeClock
import dev.studyflow.core.common.time.WallClock
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.TimeAnchor
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A controllable stand-in for the device's clocks and power state.
 *
 * The timer's hardest requirements — surviving reboots, ignoring clock changes, counting deep sleep
 * — are all things that are impossible to trigger by hand and miserable to reproduce on a real
 * device. Modelling the device as an object with [advance], [adjustWallClock] and [reboot] turns
 * each of them into a three-line deterministic JVM test.
 *
 * @param startWallClock initial wall-clock reading.
 * @param startUptime initial monotonic reading; non-zero by default because a real device has been
 *   up for a while before the app launches, and code that assumes uptime starts at zero is buggy.
 * @param startBootId identifier of the initial boot epoch.
 */
public class FakeDevice(
    startWallClock: Instant = Instant.parse("2026-03-01T09:00:00Z"),
    startUptime: Duration = Duration.ZERO,
    startBootId: String = "boot-0",
    startTimeZone: TimeZone = TimeZone.UTC,
) : AnchoredClock,
    WallClock,
    UptimeClock,
    BootIdProvider {
    private var wallClock: Instant = startWallClock
    private var uptime: Duration = startUptime
    private var bootId: BootId = BootId(startBootId)
    private var timeZone: TimeZone = startTimeZone
    private var bootCount: Int = 0

    override fun anchor(): TimeAnchor = TimeAnchor(uptime = uptime, wallClock = wallClock, bootId = bootId)

    override fun now(): Instant = wallClock

    override fun uptime(): Duration = uptime

    override fun current(): BootId = bootId

    /** The device's current zone; exposed through [FakeTimeZoneProvider]. */
    public fun currentTimeZone(): TimeZone = timeZone

    /**
     * Ordinary passage of time: both clocks move together.
     *
     * This also models deep sleep, because the app deliberately uses `elapsedRealtime`, which keeps
     * counting while the device dozes. If a test needs the two to diverge, that is a clock
     * adjustment — use [adjustWallClock].
     */
    public fun advance(duration: Duration): FakeDevice =
        apply {
            require(!duration.isNegative()) { "time only moves forward; use adjustWallClock() to change the clock" }
            uptime += duration
            wallClock += duration
        }

    /**
     * Moves the wall clock without moving the monotonic clock — an NTP correction, a manual date
     * change, or the yearly DST shuffle. A correct timer must be completely unaffected by this.
     */
    public fun adjustWallClock(delta: Duration): FakeDevice =
        apply {
            wallClock += delta
        }

    /** Simulates the user flying somewhere, or DST flipping. */
    public fun moveTo(zone: TimeZone): FakeDevice =
        apply {
            timeZone = zone
        }

    /**
     * Powers the device off for [downtime] and boots it again.
     *
     * The monotonic clock restarts from zero and the boot id changes, which is exactly the
     * situation in which an elapsed-realtime anchor from before the reboot becomes meaningless.
     */
    public fun reboot(downtime: Duration = Duration.ZERO): FakeDevice =
        apply {
            require(!downtime.isNegative()) { "downtime must not be negative" }
            wallClock += downtime
            uptime = Duration.ZERO
            bootCount += 1
            bootId = BootId("boot-$bootCount")
        }
}

/** A [TimeZoneProvider] backed by a [FakeDevice], so travel and DST are testable. */
public class FakeTimeZoneProvider(
    private val device: FakeDevice,
) : TimeZoneProvider {
    override fun current(): TimeZone = device.currentTimeZone()
}
