package dev.studyflow.feature.settings

import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.common.logging.DiagnosticKey
import dev.studyflow.core.common.logging.LogBuffer
import dev.studyflow.core.common.logging.LogEntry
import dev.studyflow.core.common.logging.diagnosticEvent
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.domain.result.DomainResult
import dev.studyflow.core.domain.result.toDomainError
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The facts a log is useless without, and nothing more.
 *
 * [apiHost] is the host of the API base URL, never the URL: a full base URL carries the project
 * reference that identifies the backend instance, and a path can carry a token. The host is enough
 * to tell "this device was talking to staging" from "this device was talking to production", which
 * is the only question a bug report asks of it.
 */
public data class LogExportEnvironment(
    val appVersion: String,
    val androidVersion: String,
    val deviceModel: String,
    val apiHost: String,
)

/** Where an export ended up. */
public sealed interface LogExportOutcome {
    /**
     * Nothing has been logged yet — a fresh process that has not failed at anything.
     *
     * Reported rather than written: a file containing only a header wastes the user's time and
     * teaches the reader nothing.
     */
    public data object Empty : LogExportOutcome

    public data class Exported(
        val fileName: String,
        /** A `content://` URI the user may share, when the writer produced one. */
        val shareUri: String?,
        /** True when the file landed in the shared Downloads folder rather than app storage. */
        val inDownloads: Boolean,
    ) : LogExportOutcome
}

/** Where an assembled log is written; behind an interface so the assembly can be tested alone. */
public interface LogExportFileWriter {
    /**
     * Writes [content] as [fileName], failing by throwing.
     *
     * @return where it landed, including a shareable URI when the platform gave one.
     */
    public suspend fun write(
        fileName: String,
        content: String,
    ): LogExportOutcome.Exported
}

/**
 * Turns the in-memory log into a file the user can attach to a bug report (issue #189 follow-up).
 *
 * The content comes from [LogBuffer] and nowhere else. The buffer already holds the sanitised form
 * — the same text a release build writes to logcat — so the export cannot become a back door that
 * ships the unscrubbed message the sanitiser exists to discard.
 */
public class LogExporter(
    private val buffer: LogBuffer,
    private val environment: LogExportEnvironment,
    private val writer: LogExportFileWriter,
    private val logger: AppLogger,
    private val clock: Clock,
    private val timeZone: () -> TimeZone = TimeZone::currentSystemDefault,
) {
    public suspend fun export(): DomainResult<LogExportOutcome> {
        val entries = buffer.snapshot()
        if (entries.isEmpty()) return DomainResult.Success(LogExportOutcome.Empty)

        val now = clock.now()
        val zone = timeZone()
        // Logged after the snapshot is taken, so the export never describes itself; it is here so
        // a second export, or a support conversation, can tell how much history the first carried.
        logger.diagnostic(
            diagnosticEvent(DiagnosticCode.LogsExported) {
                put(DiagnosticKey.Count, entries.size)
            },
        )
        return try {
            DomainResult.Success(
                writer.write(
                    fileName = logFileName(now, zone),
                    content = assembleLogExport(environment, entries, now, zone),
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Throwable,
        ) {
            DomainResult.Failure(failure.toDomainError())
        }
    }
}

/** `studyflow-logs-2026-09-30-184329.txt`, in the device's own calendar. */
internal fun logFileName(
    at: Instant,
    zone: TimeZone,
): String {
    val local = at.toLocalDateTime(zone)
    val date = local.date.toString()
    val time = listOf(local.hour, local.minute, local.second).joinToString("") { it.padded() }
    return "studyflow-logs-$date-$time.txt"
}

/**
 * The exported document: a short header, then the buffer verbatim.
 *
 * Kept as a pure function of its inputs so the thing worth testing — that the header names the
 * build and the backend host but never the full URL, and that the body is the sanitised buffer —
 * can be asserted without a device, a content resolver or a file system.
 */
internal fun assembleLogExport(
    environment: LogExportEnvironment,
    entries: List<LogEntry>,
    exportedAt: Instant,
    zone: TimeZone,
): String =
    buildString {
        appendLine("StudyFlow logs")
        appendLine("exported: ${exportedAt.toLocalDateTime(zone)}")
        appendLine("app version: ${environment.appVersion}")
        appendLine("android: ${environment.androidVersion}")
        appendLine("device: ${environment.deviceModel}")
        appendLine("api host: ${environment.apiHost}")
        appendLine("entries: ${entries.size}")
        appendLine()
        entries.forEach { entry ->
            appendLine(
                "${entry.at.toLocalDateTime(zone)} ${entry.level.name.uppercase()} ${entry.tag}: ${entry.message}",
            )
        }
    }

private fun Int.padded(): String = toString().padStart(2, '0')
