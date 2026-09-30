package dev.studyflow.core.scheduling

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import dev.studyflow.core.common.logging.LogLevel
import dev.studyflow.core.domain.sync.SyncDocument
import dev.studyflow.core.domain.sync.SyncDocumentPage
import dev.studyflow.core.domain.sync.SyncEngine
import dev.studyflow.core.domain.sync.SyncFailure
import dev.studyflow.core.domain.sync.SyncPage
import dev.studyflow.core.domain.sync.SyncPushAck
import dev.studyflow.core.domain.sync.SyncQueueItem
import dev.studyflow.core.domain.sync.SyncResult
import dev.studyflow.core.domain.sync.SyncSessionRecord
import dev.studyflow.core.domain.sync.SyncStatus
import dev.studyflow.core.domain.sync.SyncStore
import dev.studyflow.core.domain.sync.SyncTransport
import dev.studyflow.core.domain.sync.SyncTrigger
import dev.studyflow.core.network.auth.AuthTokens
import dev.studyflow.core.network.auth.InMemoryTokenStore
import dev.studyflow.core.testing.logging.RecordingAppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * How a sync outcome becomes a WorkManager verdict.
 *
 * The distinction matters more than it looks: `retry` is what arms the exponential backoff, and
 * `failure` is what stops the device retrying a request the server will keep refusing. Getting it
 * backwards would either flatten the battery or silently strand the queue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `a finished pass succeeds`() =
        runBlocking {
            val worker = worker(FailingTransport(failure = null))

            assertEquals(ListenableWorker.Result.success(), worker.doWork())
        }

    @Test
    fun `a retryable failure asks WorkManager to back off rather than giving up`() =
        runBlocking {
            val worker = worker(FailingTransport(SyncFailure("server is down", retryable = true)))

            assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        }

    @Test
    fun `a permanent failure stops instead of retrying forever`() =
        runBlocking {
            val worker = worker(FailingTransport(SyncFailure("rejected", retryable = false)))

            assertEquals(ListenableWorker.Result.failure(), worker.doWork())
        }

    @Test
    fun `an outbound run with an empty queue succeeds without touching the network`() =
        runBlocking {
            val transport = FailingTransport(SyncFailure("must not be called", retryable = true))
            val worker = worker(transport, trigger = SyncTrigger.OUTBOUND)

            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(0, transport.calls)
        }

    @Test
    fun `a local-only user never reaches the network`() =
        runBlocking {
            val transport = FailingTransport(SyncFailure("must not be called", retryable = false))
            val logger = RecordingAppLogger()
            val worker = worker(transport, signedIn = false, logger = logger)

            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(0, transport.calls)
            // Nothing reaches the store, so this line is the only trace that the run was skipped.
            assertEquals(listOf("Sync skipped: no signed-in account"), logger.messageTextsAt(LogLevel.Info))
        }

    @Test
    fun `reminders are reconciled only when another device's changes were applied`() =
        runBlocking {
            worker(FailingTransport(failure = null), store = EmptyStore(applied = 0)).doWork()
            assertEquals(0, remoteChanges)

            // A page that moves the cursor is written, and here the write reports one change.
            worker(FailingTransport(failure = null, nextCursor = "c1"), store = EmptyStore(applied = 1)).doWork()
            assertEquals(1, remoteChanges)
        }

    private var remoteChanges = 0

    private fun worker(
        transport: SyncTransport,
        trigger: SyncTrigger = SyncTrigger.SCHEDULED,
        signedIn: Boolean = true,
        store: SyncStore = EmptyStore(),
        logger: RecordingAppLogger = RecordingAppLogger(),
    ): SyncWorker {
        val engine = SyncEngine(store = store, transport = transport, clock = { NOW })
        val tokenStore = InMemoryTokenStore(if (signedIn) AuthTokens("access", "refresh") else null)
        val listener = RemoteChangesListener { remoteChanges++ }
        return TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(EXTRA_SYNC_TRIGGER to trigger.name))
            .setWorkerFactory(
                object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        SyncWorker(
                            appContext,
                            workerParameters,
                            engine,
                            tokenStore,
                            listener,
                            logger,
                        )
                },
            ).build()
    }

    /**
     * A device with nothing queued: every run is a pull, so the trigger decides the traffic.
     * [applied] is how many inbound changes each page write reports, per stream.
     */
    private class EmptyStore(
        private val applied: Int = 0,
    ) : SyncStore {
        override suspend fun pending(limit: Int): List<SyncQueueItem> = emptyList()

        override suspend fun sessionRecord(sessionId: String): SyncSessionRecord? = null

        override suspend fun acknowledge(sequences: List<Long>) = Unit

        override suspend fun applyPage(page: SyncPage): Int = applied

        override suspend fun cursor(): String? = null

        override suspend fun documentRecord(item: SyncQueueItem): SyncDocument? = null

        override suspend fun applyDocumentPage(
            page: SyncDocumentPage,
            receivedAt: Instant,
        ): Int = 0

        override suspend fun documentCursor(): String? = null

        override suspend fun resetForAccountChange() = Unit

        override suspend fun recordSuccess(at: Instant) = Unit

        override suspend fun recordFailure(
            failure: SyncFailure,
            at: Instant,
        ) = Unit

        override fun observeStatus(): Flow<SyncStatus> = flowOf(SyncStatus())
    }

    private class FailingTransport(
        private val failure: SyncFailure?,
        private val nextCursor: String? = null,
    ) : SyncTransport {
        var calls: Int = 0
            private set

        override suspend fun push(records: List<SyncSessionRecord>): SyncResult<SyncPushAck> {
            calls++
            return failure?.let { SyncResult.Failure(it) } ?: SyncResult.Success(SyncPushAck(emptySet()))
        }

        override suspend fun pull(
            cursor: String?,
            limit: Int,
        ): SyncResult<SyncPage> {
            calls++
            return failure?.let { SyncResult.Failure(it) }
                ?: SyncResult.Success(SyncPage(changes = emptyList(), nextCursor = nextCursor, hasMore = false))
        }

        override suspend fun pushDocuments(documents: List<SyncDocument>): SyncResult<SyncPushAck> {
            calls++
            return failure?.let { SyncResult.Failure(it) } ?: SyncResult.Success(SyncPushAck(emptySet()))
        }

        override suspend fun pullDocuments(
            cursor: String?,
            limit: Int,
        ): SyncResult<SyncDocumentPage> {
            calls++
            return failure?.let { SyncResult.Failure(it) }
                ?: SyncResult.Success(SyncDocumentPage(changes = emptyList(), nextCursor = null, hasMore = false))
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-01T09:00:00Z")
    }
}
