package dev.studyflow.core.common.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class LogSanitizerTest {
    @Test
    fun `release logs drop user supplied content and filenames`() {
        val message = "Upload alice@example.com from /storage/emulated/0/Documents/chemistry-notes.pdf"

        val scrubbed = LogSanitizer.scrubReleaseMessage(message)

        assertEquals(LogSanitizer.RELEASE_MESSAGE, scrubbed)
        assertFalse(scrubbed.contains("alice"))
        assertFalse(scrubbed.contains("chemistry-notes.pdf"))
    }

    @Test
    fun `release tag is stable and never caller supplied`() {
        val tag = LogSanitizer.scrubReleaseTag("chemistry-notes.pdf")

        assertEquals(LogSanitizer.RELEASE_TAG, tag)
    }

    @Test
    fun `debug logs redact common pii without dropping diagnostics`() {
        val scrubbed =
            LogSanitizer.scrubDebugMessage(
                "Failed alice@example.com at /storage/emulated/0/Documents/chemistry-notes.pdf",
            )

        assertEquals("Failed [email] at [path]", scrubbed)
    }
}
