package dev.studyflow.app.logging

import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.common.logging.DiagnosticKey
import dev.studyflow.core.common.logging.RingLogBuffer
import dev.studyflow.core.common.logging.diagnosticEvent
import dev.studyflow.core.common.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * What the exported ring buffer is allowed to spend its capacity on.
 *
 * A failure is reported twice at the call site: once as a structured [DiagnosticCode] and once as
 * the free text a developer reads in logcat. The scrubbed form of that free text is `level=Warning`
 * and nothing else, so buffering it halved the export for no diagnosis at all (issue #191).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class AndroidAppLoggerTest {
    private val buffer = RingLogBuffer(clock = Clock { Instant.fromEpochMilliseconds(0) })
    private val logger = AndroidAppLogger(buffer)

    @Test
    fun `a diagnostic and its free-text companion are exported once`() {
        logger.diagnostic(
            diagnosticEvent(DiagnosticCode.SyncFailed) {
                put(DiagnosticKey.Reason, TestReason.UNAUTHORIZED)
                put(DiagnosticKey.Retryable, false)
            },
        )
        logger.warning("SyncWorker", "Sync failed: sign in again")

        assertEquals(
            listOf("code=SyncFailed reason=UNAUTHORIZED retryable=false"),
            buffer.snapshot().map { it.message },
        )
    }

    @Test
    fun `a throwable still reaches the export, because its type is a diagnosis`() {
        logger.error("Materials", "upload failed", IllegalStateException("boom"))

        assertEquals(
            listOf("level=Error throwable=IllegalStateException"),
            buffer.snapshot().map { it.message },
        )
    }

    private enum class TestReason { UNAUTHORIZED }
}
