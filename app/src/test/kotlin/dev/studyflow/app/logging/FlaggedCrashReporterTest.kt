package dev.studyflow.app.logging

import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.LogLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlaggedCrashReporterTest {
    @Test
    fun `record is no-op when crash reporting is disabled`() {
        val logger = RecordingLogger()
        val reporter = FlaggedCrashReporter(logger)

        reporter.initialize(enabled = false, optedOut = false)
        reporter.record(RuntimeException("user content"))

        assertTrue(logger.events.isEmpty())
    }

    @Test
    fun `record is no-op when user opted out`() {
        val logger = RecordingLogger()
        val reporter = FlaggedCrashReporter(logger)

        reporter.initialize(enabled = true, optedOut = true)
        reporter.record(RuntimeException("user content"))

        assertTrue(logger.events.isEmpty())
    }

    @Test
    fun `record delegates to logger when active`() {
        val logger = RecordingLogger()
        val reporter = FlaggedCrashReporter(logger)
        val throwable = IllegalStateException("user content")

        reporter.initialize(enabled = true, optedOut = false)
        logger.events.clear()
        reporter.record(throwable)

        val event = logger.events.single()
        assertEquals(LogLevel.Error, event.level)
        assertEquals("CrashReporter", event.tag)
        assertEquals("Unhandled exception", event.message)
        assertSame(throwable, event.throwable)
    }

    private class RecordingLogger : AppLogger {
        val events = mutableListOf<Event>()

        override fun log(
            level: LogLevel,
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            events += Event(level, tag, message, throwable)
        }
    }

    private data class Event(
        val level: LogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
    )
}
