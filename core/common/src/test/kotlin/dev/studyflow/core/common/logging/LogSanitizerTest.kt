package dev.studyflow.core.common.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class LogSanitizerTest {
    @Test
    fun `release logs drop user supplied content and filenames`() {
        val message = "Upload alice@example.com from /storage/emulated/0/Documents/chemistry-notes.pdf"

        val scrubbed = LogSanitizer.scrubReleaseMessage(LogLevel.Error)

        assertEquals("level=Error", scrubbed)
        assertFalse(scrubbed.contains("alice"))
        assertFalse(scrubbed.contains("chemistry-notes.pdf"))
        assertFalse(scrubbed.contains(message))
    }

    @Test
    fun `release tag is stable and never caller supplied`() {
        val unsafeTag = "chemistry-notes.pdf"

        val tag = LogSanitizer.RELEASE_TAG

        assertEquals(LogSanitizer.RELEASE_TAG, tag)
        assertFalse(tag.contains(unsafeTag))
    }

    @Test
    fun `release message preserves sanitized throwable type`() {
        val scrubbed = LogSanitizer.scrubReleaseMessage(LogLevel.Warning, "Bad/FileException.kt")

        assertEquals("level=Warning throwable=BadFileExceptionkt", scrubbed)
        assertFalse(scrubbed.contains("Bad/FileException.kt"))
        assertFalse(scrubbed.contains(".kt"))
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
