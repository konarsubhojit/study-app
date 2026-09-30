package dev.studyflow.core.common.logging

import dev.studyflow.core.common.time.Clock
import kotlin.time.Instant

/** One line as it was written to the log, already sanitised. */
public data class LogEntry(
    val at: Instant,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * The recent log, kept in memory so the user can hand it over when reporting a problem.
 *
 * It holds exactly what a release build writes to logcat — the sanitised form — rather than the
 * original text. Keeping the unscrubbed message "just for the export" would make this a way around
 * [LogSanitizer] rather than a companion to it.
 */
public interface LogBuffer {
    public fun record(
        level: LogLevel,
        tag: String,
        message: String,
    )

    /** Everything held right now, oldest first. */
    public fun snapshot(): List<LogEntry>

    /** Forgets everything; called when the signed-in account changes. */
    public fun clear()
}

/**
 * A fixed-capacity [LogBuffer]: the oldest entry is evicted to make room for the newest.
 *
 * Capacity is deliberately small. Each entry is a single short, sanitised line (well under 200
 * bytes), so [DEFAULT_CAPACITY] costs tens of kilobytes — small enough to hold on a low-memory
 * device for the whole life of the process, and long enough to cover a failing sync run and the
 * minute of activity around it, which is the window a bug report is written about. A larger buffer
 * would mostly retain lines nobody will read.
 *
 * Every method is synchronised on one lock: workers, coroutines on several dispatchers and the
 * main thread all log, and an [ArrayDeque] is not thread-safe.
 */
public class RingLogBuffer(
    private val clock: Clock,
    private val capacity: Int = DEFAULT_CAPACITY,
) : LogBuffer {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>(capacity)

    override fun record(
        level: LogLevel,
        tag: String,
        message: String,
    ) {
        val entry = LogEntry(at = clock.now(), level = level, tag = tag, message = message)
        synchronized(lock) {
            if (entries.size == capacity) entries.removeFirst()
            entries.addLast(entry)
        }
    }

    override fun snapshot(): List<LogEntry> = synchronized(lock) { entries.toList() }

    override fun clear() {
        synchronized(lock) { entries.clear() }
    }

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 300
    }
}
