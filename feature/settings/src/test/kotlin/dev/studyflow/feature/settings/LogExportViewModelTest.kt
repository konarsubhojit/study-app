package dev.studyflow.feature.settings

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.studyflow.core.common.logging.LogLevel
import dev.studyflow.core.common.logging.RingLogBuffer
import dev.studyflow.core.domain.result.StorageException
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import dev.studyflow.core.testing.logging.RecordingAppLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("LogExportViewModel")
class LogExportViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val buffer = RingLogBuffer(clock = { NOW })
    private val writer = FakeWriter()

    @Test
    fun `a successful export names the file and offers to share it`() =
        runTest(mainDispatcher.dispatcher) {
            buffer.record(LogLevel.Warning, "Sync", "code=SyncFailed stage=PULL reason=OFFLINE retryable=true")
            val viewModel = viewModel()

            viewModel.onEvent(LogExportUiEvent.ExportRequested)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(LogExportMessage.Exported("studyflow-logs-2026-09-30-184329.txt", inDownloads = true), state.message)
            assertEquals("content://downloads/1", state.shareUri)
        }

    @Test
    fun `sharing asks the screen to open a chooser for the file just written`() =
        runTest(mainDispatcher.dispatcher) {
            buffer.record(LogLevel.Info, "Sync", "code=SyncSkippedSignedOut trigger=MANUAL")
            val viewModel = viewModel()
            viewModel.onEvent(LogExportUiEvent.ExportRequested)
            advanceUntilIdle()

            viewModel.effects.test {
                viewModel.onEvent(LogExportUiEvent.ShareRequested)
                assertEquals(LogExportUiEffect.ShareFile("content://downloads/1"), awaitItem())
            }
        }

    @Test
    fun `nothing logged yet is said plainly instead of offering an empty file`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(LogExportUiEvent.ExportRequested)
            advanceUntilIdle()

            assertEquals(LogExportMessage.Empty, viewModel.state.value.message)
            assertNull(viewModel.state.value.shareUri, "there is no file to share")
        }

    @Test
    fun `a failed write is reported without an exception reaching the screen`() =
        runTest(mainDispatcher.dispatcher) {
            buffer.record(LogLevel.Info, "Sync", "code=SyncSkippedSignedOut trigger=MANUAL")
            writer.failure = StorageException("no space")
            val viewModel = viewModel()

            viewModel.onEvent(LogExportUiEvent.ExportRequested)
            advanceUntilIdle()

            assertEquals(
                "The logs could not be saved. There was not enough space, or the file could not be written.",
                (viewModel.state.value.message as LogExportMessage.Failed).text(),
            )
        }

    private fun viewModel() =
        LogExportViewModel(
            savedStateHandle = SavedStateHandle(),
            exporter =
                LogExporter(
                    buffer = buffer,
                    environment = ENVIRONMENT,
                    writer = writer,
                    logger = RecordingAppLogger(),
                    clock = { NOW },
                    timeZone = { TimeZone.UTC },
                ),
        )

    private class FakeWriter : LogExportFileWriter {
        var failure: Throwable? = null

        override suspend fun write(
            fileName: String,
            content: String,
        ): LogExportOutcome.Exported {
            failure?.let { throw it }
            return LogExportOutcome.Exported(fileName = fileName, shareUri = "content://downloads/1", inDownloads = true)
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-30T18:43:29Z")
        val ENVIRONMENT =
            LogExportEnvironment(
                appVersion = "1.4.0 (140)",
                androidVersion = "Android 15 (API 35)",
                deviceModel = "Google Pixel 8",
                apiHost = "api.studyflow.dev",
            )
    }
}
