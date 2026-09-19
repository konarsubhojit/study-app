package dev.studyflow.core.domain.sync

import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.testing.data.testStudySession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * The rules about *when* sync talks to the server, which are as much a feature as the merge.
 *
 * Two of the acceptance criteria are about restraint — no request when there is nothing to send or
 * fetch — and one is about interruption, which on a phone is the normal case rather than the edge
 * case. All three are checked here against fakes, because they are properties of the ordering
 * rather than of HTTP or Room.
 */
@DisplayName("SyncEngine")
class SyncEngineTest {
    private val store = FakeSyncStore()
    private val transport = FakeSyncTransport()
    private val clock = Clock { NOW }

    @Nested
    @DisplayName("spending the user's data")
    inner class Restraint {
        @Test
        fun `a mutation-triggered run with an empty queue makes no request at all`() =
            runTest {
                val outcome = engine().sync(SyncTrigger.OUTBOUND)

                assertEquals(SyncOutcome.Idle, outcome)
                assertEquals(0, transport.pushes)
                assertEquals(0, transport.pulls)
            }

        @Test
        fun `a scheduled run with nothing to send spends one pull and writes nothing`() =
            runTest {
                transport.pages = listOf(SyncPage(changes = emptyList(), nextCursor = null, hasMore = false))

                val outcome = engine().sync(SyncTrigger.SCHEDULED)

                assertEquals(SyncOutcome.Synced(pushed = 0, applied = 0), outcome)
                assertEquals(1, transport.pulls)
                assertEquals(0, store.appliedPages)
            }

        @Test
        fun `an unchanged cursor is not written back to the database`() =
            runTest {
                store.cursor = "cursor-9"
                transport.pages = listOf(SyncPage(changes = emptyList(), nextCursor = "cursor-9", hasMore = false))

                engine().sync(SyncTrigger.MANUAL)

                assertEquals(0, store.appliedPages)
            }
    }

    @Nested
    @DisplayName("draining the queue")
    inner class Draining {
        @Test
        fun `the queue is sent in batches and acknowledged by sequence`() =
            runTest {
                store.enqueue(sessions = (1..3).map { "session-$it" })

                val outcome = engine(batchSize = 2).sync(SyncTrigger.OUTBOUND)

                assertEquals(SyncOutcome.Synced(pushed = 3, applied = 0), outcome)
                assertEquals(2, transport.pushes)
                assertEquals(listOf(listOf(1L, 2L), listOf(3L)), store.acknowledged)
                assertTrue(store.pending(limit = 10).isEmpty())
            }

        @Test
        fun `a change queued while a batch is in flight survives the acknowledgement`() =
            runTest {
                store.enqueue(sessions = listOf("session-1"))
                transport.beforePush = { store.enqueue(sessions = listOf("session-1")) }

                engine().sync(SyncTrigger.OUTBOUND)

                // Re-queuing replaces the entry under a *new* sequence, so acknowledging the batch
                // that was in flight retires only sequence 1. The newer change is still pending
                // afterwards and goes out in its own batch, rather than being silently dropped.
                assertEquals(listOf(listOf(1L), listOf(2L)), store.acknowledged)
                assertEquals(2, transport.pushes)
            }

        @Test
        fun `a failed push leaves the queue intact and is reported`() =
            runTest {
                store.enqueue(sessions = listOf("session-1"))
                transport.pushFailure = SyncFailure("offline", retryable = true)

                val outcome = engine().sync(SyncTrigger.OUTBOUND)

                assertEquals(SyncOutcome.Failed(SyncFailure("offline", retryable = true)), outcome)
                assertEquals(1, store.pending(limit = 10).size)
                assertEquals(0, transport.pulls)
                assertEquals("offline", store.status.value.lastError)
            }

        @Test
        fun `a queue entry whose session has vanished is retired rather than retried forever`() =
            runTest {
                store.enqueue(sessions = listOf("session-gone"))
                store.records.clear()

                val outcome = engine().sync(SyncTrigger.OUTBOUND)

                assertEquals(SyncOutcome.Idle, outcome)
                assertEquals(0, transport.pushes)
                assertTrue(store.pending(limit = 10).isEmpty())
            }
    }

    @Nested
    @DisplayName("reading the delta")
    inner class Delta {
        @Test
        fun `pages are followed until the server says there are no more`() =
            runTest {
                transport.pages =
                    listOf(
                        SyncPage(changes = listOf(record("session-1")), nextCursor = "cursor-1", hasMore = true),
                        SyncPage(changes = listOf(record("session-2")), nextCursor = "cursor-2", hasMore = false),
                    )

                val outcome = engine().sync(SyncTrigger.SCHEDULED)

                assertEquals(SyncOutcome.Synced(pushed = 0, applied = 2), outcome)
                assertEquals("cursor-2", store.cursor)
            }

        @Test
        fun `an interrupted run resumes from the last page it finished`() =
            runTest {
                transport.pages =
                    listOf(
                        SyncPage(changes = listOf(record("session-1")), nextCursor = "cursor-1", hasMore = true),
                        SyncPage(changes = listOf(record("session-2")), nextCursor = "cursor-2", hasMore = true),
                    )

                // The kill happens after the first page has been applied, exactly as a process
                // death between two pages would.
                engine(maxPagesPerRun = 1).sync(SyncTrigger.SCHEDULED)
                assertEquals("cursor-1", store.cursor)

                engine(maxPagesPerRun = 1).sync(SyncTrigger.SCHEDULED)

                assertEquals("cursor-2", store.cursor)
                assertEquals(listOf(null, "cursor-1"), transport.requestedCursors)
            }

        @Test
        fun `a failed pull is recorded and does not move the cursor`() =
            runTest {
                transport.pullFailure = SyncFailure("server is down", retryable = true)

                val outcome = engine().sync(SyncTrigger.MANUAL)

                assertInstanceOf(SyncOutcome.Failed::class.java, outcome)
                assertNull(store.cursor)
                assertEquals("server is down", store.status.value.lastError)
                assertNull(store.status.value.lastSuccessAt)
            }

        @Test
        fun `a successful run clears the error the previous one left behind`() =
            runTest {
                transport.pullFailure = SyncFailure("server is down")
                engine().sync(SyncTrigger.MANUAL)

                transport.pullFailure = null
                transport.pages = listOf(SyncPage(changes = emptyList(), nextCursor = null, hasMore = false))
                engine().sync(SyncTrigger.MANUAL)

                assertNull(store.status.value.lastError)
                assertEquals(NOW, store.status.value.lastSuccessAt)
            }
    }

    private fun engine(
        batchSize: Int = 50,
        maxPagesPerRun: Int = 10,
    ) = SyncEngine(
        store = store,
        transport = transport,
        clock = clock,
        batchSize = batchSize,
        maxPagesPerRun = maxPagesPerRun,
    )

    private fun record(id: String) =
        SyncSessionRecord(
            session = testStudySession(id = id, status = SessionStatus.STOPPED, updatedAt = NOW),
            events = emptyList(),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-01T10:00:00Z")
    }
}

/** An in-memory [SyncStore] that records what the engine asked it to do. */
private class FakeSyncStore : SyncStore {
    val status = MutableStateFlow(SyncStatus())
    val records = mutableMapOf<String, SyncSessionRecord>()
    val queue = mutableListOf<SyncQueueItem>()
    val acknowledged = mutableListOf<List<Long>>()
    var cursor: String? = null
    var appliedPages = 0
    private var nextSequence = 1L

    fun enqueue(sessions: List<String>) {
        sessions.forEach { id ->
            queue.removeAll { it.entityId == id }
            queue +=
                SyncQueueItem(
                    sequence = nextSequence++,
                    entityType = SyncEntityType.SESSION,
                    entityId = id,
                    operation = SyncOperation.UPSERT,
                    updatedAt = Instant.parse("2026-03-01T09:00:00Z"),
                    deviceId = "device-a",
                )
            records[id] =
                SyncSessionRecord(
                    session =
                        testStudySession(
                            id = id,
                            status = SessionStatus.STOPPED,
                            updatedAt = Instant.parse("2026-03-01T09:00:00Z"),
                        ),
                    events = emptyList(),
                )
        }
        status.value = status.value.copy(pendingCount = queue.size)
    }

    override fun observeStatus(): Flow<SyncStatus> = status

    override suspend fun pending(limit: Int): List<SyncQueueItem> = queue.sortedBy { it.sequence }.take(limit)

    override suspend fun sessionRecord(sessionId: String): SyncSessionRecord? = records[sessionId]

    override suspend fun acknowledge(sequences: List<Long>) {
        acknowledged += sequences
        queue.removeAll { it.sequence in sequences }
        status.value = status.value.copy(pendingCount = queue.size)
    }

    override suspend fun applyPage(page: SyncPage): Int {
        appliedPages++
        page.changes.forEach { records[it.session.id] = it }
        page.nextCursor?.let { cursor = it }
        return page.changes.size
    }

    override suspend fun cursor(): String? = cursor

    override suspend fun recordSuccess(at: Instant) {
        status.value = status.value.copy(lastSuccessAt = at, lastError = null, lastAttemptAt = at)
    }

    override suspend fun recordFailure(
        failure: SyncFailure,
        at: Instant,
    ) {
        status.value = status.value.copy(lastError = failure.message, lastAttemptAt = at)
    }
}

/** A [SyncTransport] that answers from a script and counts what it was asked for. */
private class FakeSyncTransport : SyncTransport {
    var pages: List<SyncPage> = emptyList()
    var pushFailure: SyncFailure? = null
    var pullFailure: SyncFailure? = null
    var beforePush: (() -> Unit)? = null
    var pushes = 0
    var pulls = 0
    val requestedCursors = mutableListOf<String?>()
    private var pageIndex = 0

    override suspend fun push(records: List<SyncSessionRecord>): SyncResult<SyncPushAck> {
        // One-shot: the hook models a single mutation racing a single in-flight batch.
        beforePush?.invoke()
        beforePush = null
        pushes++
        pushFailure?.let { return SyncResult.Failure(it) }
        return SyncResult.Success(SyncPushAck(acceptedIds = records.map { it.session.id }.toSet()))
    }

    override suspend fun pull(
        cursor: String?,
        limit: Int,
    ): SyncResult<SyncPage> {
        pulls++
        requestedCursors += cursor
        pullFailure?.let { return SyncResult.Failure(it) }
        val page =
            pages.getOrNull(pageIndex)?.also { pageIndex++ }
                ?: SyncPage(changes = emptyList(), nextCursor = cursor, hasMore = false)
        return SyncResult.Success(page)
    }
}
