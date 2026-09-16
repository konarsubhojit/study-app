package dev.studyflow.app.logging

import dev.studyflow.core.common.logging.LogLevel
import dev.studyflow.core.testing.logging.RecordingAppLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlaggedCrashReporterTest {
    @Test
    fun `record is no-op when crash reporting is disabled`() {
        val logger = RecordingAppLogger()
        val reporter = FlaggedCrashReporter(logger)

        reporter.initialize(enabled = false, optedOut = false)
        reporter.record(RuntimeException("user content"))

        assertTrue(logger.messages.isEmpty())
    }

    @Test
    fun `record is no-op when user opted out`() {
        val logger = RecordingAppLogger()
        val reporter = FlaggedCrashReporter(logger)

        reporter.initialize(enabled = true, optedOut = true)
        reporter.record(RuntimeException("user content"))

        assertTrue(logger.messages.isEmpty())
    }

    @Test
    fun `record delegates to logger when active`() {
        val logger = RecordingAppLogger()
        val reporter = FlaggedCrashReporter(logger)
        val throwable = IllegalStateException("user content")

        reporter.initialize(enabled = true, optedOut = false)
        logger.clear()
        reporter.record(throwable)

        val event = logger.messages.single()
        assertEquals(LogLevel.Error, event.level)
        assertEquals("CrashReporter", event.tag)
        assertEquals("Unhandled exception", event.message)
        assertSame(throwable, event.throwable)
    }
}
