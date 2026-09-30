package dev.studyflow.core.common.logging

/** Application logging facade so feature code never binds itself to a concrete logger. */
public interface AppLogger {
    public fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )

    /**
     * Logs a structured [DiagnosticEvent], which survives release sanitisation.
     *
     * Deliberately not a default that delegates to [log]: a free-text message is scrubbed away in
     * release builds, so an implementation that quietly routed diagnostics through it would
     * reintroduce the very blindness this exists to fix.
     */
    public fun diagnostic(
        event: DiagnosticEvent,
        throwable: Throwable? = null,
    )

    public fun debug(
        tag: String,
        message: String,
    ): Unit = log(LogLevel.Debug, tag, message)

    public fun info(
        tag: String,
        message: String,
    ): Unit = log(LogLevel.Info, tag, message)

    public fun warning(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ): Unit = log(LogLevel.Warning, tag, message, throwable)

    public fun error(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ): Unit = log(LogLevel.Error, tag, message, throwable)
}

public enum class LogLevel {
    Debug,
    Info,
    Warning,
    Error,
}
