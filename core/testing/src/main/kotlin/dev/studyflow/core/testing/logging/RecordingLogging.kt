package dev.studyflow.core.testing.logging

import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.CrashReporter
import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.common.logging.DiagnosticEvent
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
    private val recordedDiagnostics = mutableListOf<LoggedDiagnostic>()

    /** Everything logged so far, oldest first. */
    public val messages: List<LoggedMessage> get() = recorded.toList()

    /**
     * Structured diagnostics, oldest first, kept apart from free text.
     *
     * The two survive release builds differently — free text is scrubbed away, a diagnostic is
     * not — so a test that means "this is diagnosable in production" has to be able to say which
     * of the two it asserted on.
     */
    public val diagnostics: List<LoggedDiagnostic> get() = recordedDiagnostics.toList()

    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        recorded += LoggedMessage(level = level, tag = tag, message = message, throwable = throwable)
    }

    override fun diagnostic(
        event: DiagnosticEvent,
        throwable: Throwable?,
    ) {
        recordedDiagnostics += LoggedDiagnostic(code = event.code, rendered = event.render(throwable))
    }

    /** The rendered lines of every diagnostic with [code], which is what release logcat shows. */
    public fun diagnosticsWith(code: DiagnosticCode): List<String> =
        recordedDiagnostics.filter { it.code == code }.map(LoggedDiagnostic::rendered)

    /** The message texts logged at [level], which is usually all a test cares about. */
    public fun messageTextsAt(level: LogLevel): List<String> = recorded.filter { it.level == level }.map { it.message }

    /** Forgets everything recorded so far. */
    public fun clear() {
        recorded.clear()
        recordedDiagnostics.clear()
    }
}

/** One captured call to [AppLogger.diagnostic], with the line it would have written. */
public data class LoggedDiagnostic(
    val code: DiagnosticCode,
    val rendered: String,
)

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

    /** True while reporting is switched on: [initialize] was called with it enabled and no opt-out. */
    public var isReporting: Boolean = false
        private set

    /** Throwables passed to [record], oldest first. */
    public val recordedThrowables: List<Throwable> get() = recorded.toList()

    override fun initialize(
        enabled: Boolean,
        optedOut: Boolean,
    ) {
        isReporting = enabled && !optedOut
    }

    override fun record(throwable: Throwable) {
        if (isReporting) {
            recorded += throwable
        }
    }
}
