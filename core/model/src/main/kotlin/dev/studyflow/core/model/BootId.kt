package dev.studyflow.core.model

/**
 * Identifies a single boot epoch of the device.
 *
 * `elapsedRealtime` is only monotonic *within* one boot: after a restart it resets to zero, so an
 * elapsed-realtime reading is meaningless unless you also know which boot produced it. Every
 * [SessionEvent] therefore records the boot it was written in, which is what lets the timer tell
 * "the device was awake for 40 minutes" apart from "the device rebooted and we have no idea".
 *
 * @property value opaque, stable-per-boot identifier (on Android, derived from
 *   `android.os.Build.BOOT_ID` where available, otherwise a value persisted at first boot).
 */
@JvmInline
public value class BootId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "BootId must not be blank" }
    }

    override fun toString(): String = value
}
