package dev.studyflow.feature.settings

import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.common.logging.LogLevel
import dev.studyflow.core.common.logging.RingLogBuffer
import dev.studyflow.core.domain.result.DomainError
import dev.studyflow.core.domain.result.DomainResult
import dev.studyflow.core.domain.result.StorageException
import dev.studyflow.core.testing.logging.RecordingAppLogger
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * What a user actually hands over when they tap "Export logs".
 *
 * The MediaStore write is behind [LogExportFileWriter] so this can assert on the document itself:
 * that it names the build and the backend host, that its body is the sanitised buffer, and that a
 * process which has logged nothing produces no file at all.
 */
class LogExporterTest {
    private val clock = { EXPORTED_AT }
    private val buffer = RingLogBuffer(clock = { LOGGED_AT })
    private val writer = RecordingWriter()
    private val logger: AppLogger = RecordingAppLogger()

    private fun exporter() =
        LogExporter(
            buffer = buffer,
            environment = ENVIRONMENT,
            writer = writer,
            logger = logger,
            clock = clock,
            timeZone = { TimeZone.UTC },
        )

    @Test
    fun `the file is named for the moment it was exported`() =
        runTest {
            buffer.record(LogLevel.Warning, "Sync", "code=SyncFailed stage=PULL reason=OFFLINE retryable=true")

            val outcome = exporter().export()

            val exported = (outcome as DomainResult.Success).value as LogExportOutcome.Exported
            assertEquals("studyflow-logs-2026-09-30-184329.txt", exported.fileName)
            assertTrue(exported.inDownloads)
        }

    @Test
    fun `the header names the build and the backend host but never the whole url`() =
        runTest {
            buffer.record(LogLevel.Info, "Sync", "code=SyncSkippedSignedOut trigger=SCHEDULED")

            exporter().export()

            val content = writer.written.single().second
            assertTrue(content.contains("app version: 1.4.0 (140)"), content)
            assertTrue(content.contains("android: Android 15 (API 35)"), content)
            assertTrue(content.contains("device: Google Pixel 8"), content)
            assertTrue(content.contains("api host: api.studyflow.dev"), content)
            assertFalse(content.contains("https://"), "the full base URL must not travel with the log")
            assertFalse(content.contains("project-ref"), content)
        }

    @Test
    fun `the body is the buffer, which already holds only sanitized text`() =
        runTest {
            buffer.record(LogLevel.Warning, "StudyFlow", "level=Warning throwable=IllegalStateException")
            buffer.record(LogLevel.Warning, "Sync", "code=SyncFailed stage=PUSH reason=UNAUTHORIZED retryable=false")

            exporter().export()

            val body =
                writer.written
                    .single()
                    .second
                    .substringAfter("\n\n")
            assertEquals(
                listOf(
                    "2026-09-30T18:00 WARNING StudyFlow: level=Warning throwable=IllegalStateException",
                    "2026-09-30T18:00 WARNING Sync: code=SyncFailed stage=PUSH reason=UNAUTHORIZED retryable=false",
                ),
                body.trim().lines(),
            )
        }

    @Test
    fun `an empty buffer produces a message rather than an empty file`() =
        runTest {
            val outcome = exporter().export()

            assertEquals(DomainResult.Success(LogExportOutcome.Empty), outcome)
            assertTrue(writer.written.isEmpty(), "a header with no log in it helps nobody")
        }

    @Test
    fun `a write that fails becomes a user-safe error rather than a crash`() =
        runTest {
            buffer.record(LogLevel.Info, "Sync", "code=SyncSkippedSignedOut trigger=MANUAL")
            writer.failure = StorageException("no space")

            val outcome = exporter().export()

            assertEquals(DomainResult.Failure(DomainError.Storage), outcome)
        }

    @Test
    fun `the export itself is recorded, after the snapshot it describes`() =
        runTest {
            buffer.record(LogLevel.Info, "Sync", "code=SyncSkippedSignedOut trigger=MANUAL")
            val recording = RecordingAppLogger()
            LogExporter(
                buffer = buffer,
                environment = ENVIRONMENT,
                writer = writer,
                logger = recording,
                clock = clock,
                timeZone = { TimeZone.UTC },
            ).export()

            assertEquals(listOf("code=LogsExported count=1"), recording.diagnosticsWith(DiagnosticCode.LogsExported))
            assertFalse(
                writer.written
                    .single()
                    .second
                    .contains("LogsExported"),
                "the export must not describe itself",
            )
        }

    private class RecordingWriter : LogExportFileWriter {
        val written = mutableListOf<Pair<String, String>>()
        var failure: Throwable? = null

        override suspend fun write(
            fileName: String,
            content: String,
        ): LogExportOutcome.Exported {
            failure?.let { throw it }
            written += fileName to content
            return LogExportOutcome.Exported(
                fileName = fileName,
                shareUri = "content://downloads/1",
                inDownloads = true,
            )
        }
    }

    private companion object {
        val EXPORTED_AT: Instant = Instant.parse("2026-09-30T18:43:29Z")
        val LOGGED_AT: Instant = Instant.parse("2026-09-30T18:00:00Z")
        val ENVIRONMENT =
            LogExportEnvironment(
                appVersion = "1.4.0 (140)",
                androidVersion = "Android 15 (API 35)",
                deviceModel = "Google Pixel 8",
                apiHost = "api.studyflow.dev",
            )
    }
}
