package dev.studyflow.core.common.logging

/** Application logging facade so feature code never binds itself to a concrete logger. */
public interface AppLogger {
    public fun log(
        level: LogLevel,
        tag: String,
        message: String,
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
