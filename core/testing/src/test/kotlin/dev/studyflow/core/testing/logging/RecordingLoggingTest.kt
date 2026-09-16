package dev.studyflow.core.testing.logging

import dev.studyflow.core.common.logging.LogLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("logging fakes")
class RecordingLoggingTest {
    @Test
    fun `logged messages are captured in order with their level`() {
        val logger = RecordingAppLogger()

        logger.info("timer", "session started")
        logger.error("timer", "session lost", IllegalStateException("boom"))

        assertEquals(listOf("session started", "session lost"), logger.messages.map { it.message })
        assertEquals(listOf("session lost"), logger.messagesAt(LogLevel.Error))
        assertEquals(
            "boom",
            logger.messages
                .last()
                .throwable
                ?.message,
        )
    }

    @Test
    fun `clearing forgets earlier messages`() {
        val logger = RecordingAppLogger()
        logger.debug("timer", "noise from set-up")

        logger.clear()

        assertEquals(emptyList<LoggedMessage>(), logger.messages)
    }

    @Test
    fun `the crash reporter stays uninitialised when the user opted out`() {
        val reporter = RecordingCrashReporter()

        reporter.initialize(enabled = true, optedOut = true)

        assertFalse(reporter.isInitialized)
    }

    @Test
    fun `recorded throwables are available for assertions`() {
        val reporter = RecordingCrashReporter()
        reporter.initialize(enabled = true, optedOut = false)
        val failure = IllegalArgumentException("bad anchor")

        reporter.record(failure)

        assertTrue(reporter.isInitialized)
        assertEquals(listOf(failure), reporter.recordedThrowables)
    }
}
