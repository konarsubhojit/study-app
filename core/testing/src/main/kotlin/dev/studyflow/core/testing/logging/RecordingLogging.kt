package dev.studyflow.core.testing.logging

import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.CrashReporter
import dev.studyflow.core.common.logging.LogLevel

/** One captured call to [AppLogger.log]. */
public data class LoggedMessage(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
)

/**
 * An [AppLogger] that remembers instead of printing.
 *
 * Logging is a real requirement — "the app must not log a file name or a token" is a privacy
 * promise this project makes — and a promise that is never asserted on is a promise that quietly
 * breaks. Capturing the calls lets a test state that expectation directly.
 */
public class RecordingAppLogger : AppLogger {
    private val recorded = mutableListOf<LoggedMessage>()

    /** Everything logged so far, oldest first. */
    public val messages: List<LoggedMessage> get() = recorded.toList()

    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        recorded += LoggedMessage(level = level, tag = tag, message = message, throwable = throwable)
    }

    /** The messages logged at [level], which is usually all a test cares about. */
    public fun messagesAt(level: LogLevel): List<String> = recorded.filter { it.level == level }.map { it.message }

    /** Forgets everything recorded so far. */
    public fun clear() {
        recorded.clear()
    }
}

/**
 * A [CrashReporter] that records how it was configured and what it was handed.
 *
 * The interesting assertions about crash reporting are negative ones — nothing is recorded when the
 * user has opted out — and those are impossible to make against a real SDK without a network and a
 * project on someone's dashboard. The fake therefore honours the flags it was initialised with
 * rather than recording unconditionally: a reporter that is off does not report.
 */
public class RecordingCrashReporter : CrashReporter {
    private val recorded = mutableListOf<Throwable>()

    /** True once [initialize] has been called with reporting enabled and no opt-out. */
    public var isInitialized: Boolean = false
        private set

    /** Throwables passed to [record], oldest first. */
    public val recordedThrowables: List<Throwable> get() = recorded.toList()

    override fun initialize(
        enabled: Boolean,
        optedOut: Boolean,
    ) {
        isInitialized = enabled && !optedOut
    }

    override fun record(throwable: Throwable) {
        if (isInitialized) {
            recorded += throwable
        }
    }
}
