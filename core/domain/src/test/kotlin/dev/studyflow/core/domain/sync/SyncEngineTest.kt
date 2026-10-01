package dev.studyflow.core.domain.sync

import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.testing.data.testMaterial
import dev.studyflow.core.testing.data.testStudySession
import dev.studyflow.core.testing.data.testStudyTask
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
                assertEquals(1, transport.documentPulls)
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

    @Nested
    @DisplayName("tasks and materials")
    inner class Documents {
        @Test
        fun `a mixed batch sends sessions and documents on their own streams before acknowledging`() =
            runTest {
                store.enqueue(sessions = listOf("session-1"))
                store.enqueueTask(testStudyTask(id = "task-1", updatedAt = NOW))

                val outcome = engine().sync(SyncTrigger.OUTBOUND)

                assertEquals(
                    SyncOutcome.Synced(pushed = 2, applied = 0, recordsPushed = 1),
                    outcome,
                )
                assertEquals(1, transport.pushes)
                assertEquals(listOf(listOf("task-1")), transport.pushedDocuments)
                assertTrue(store.queue.isEmpty(), "both entries are acknowledged")
            }

        @Test
        fun `a queued uploaded material is sent through the record push`() =
            runTest {
                val material =
                    testMaterial(id = "material-1")
                        .copy(updatedAt = NOW, sync = SyncState.Synced, remoteKey = "materials/hash")
                store.enqueueMaterial(material)

                val outcome = engine().sync(SyncTrigger.OUTBOUND)

                assertEquals(
                    SyncOutcome.Synced(pushed = 1, applied = 0, recordsPushed = 1),
                    outcome,
                )
                assertEquals(listOf(listOf("material-1")), transport.pushedDocuments)
                assertTrue(store.queue.isEmpty())
            }

        @Test
        fun `a record cursor returned by one pull is persisted and sent on the next pull`() =
            runTest {
                transport.documentPages =
                    listOf(
                        SyncDocumentPage(
                            changes = listOf(SyncTaskRecord(testStudyTask(id = "task-9", updatedAt = NOW), "device-b")),
                            nextCursor = "records-5",
                            hasMore = false,
                        ),
                        SyncDocumentPage(changes = emptyList(), nextCursor = "records-5", hasMore = false),
                    )

                val first = engine().sync(SyncTrigger.SCHEDULED)
                val second = engine().sync(SyncTrigger.SCHEDULED)

                assertEquals(
                    SyncOutcome.Synced(
                        pushed = 0,
                        applied = 1,
                        recordsPulled = 1,
                        recordCursorAdvanced = true,
                    ),
                    first,
                )
                assertEquals(SyncOutcome.Synced(pushed = 0, applied = 0), second)
                assertEquals(listOf(null, "records-5"), transport.requestedDocumentCursors)
                assertEquals("records-5", store.documentCursor)
            }

        @Test
        fun `a failed document push leaves the whole batch queued`() =
            runTest {
                store.enqueue(sessions = listOf("session-1"))
                store.enqueueTask(testStudyTask(id = "task-1", updatedAt = NOW))
                transport.documentPushFailure = SyncFailure("offline")

                val outcome = engine().sync(SyncTrigger.OUTBOUND)

                assertInstanceOf(SyncOutcome.Failed::class.java, outcome)
                assertEquals(2, store.queue.size)
            }

        @Test
        fun `the record delta is read after the session delta and resumes from its own cursor`() =
            runTest {
                store.documentCursor = "records-4"
                transport.documentPages =
                    listOf(
                        SyncDocumentPage(
                            changes = listOf(SyncTaskRecord(testStudyTask(id = "task-9", updatedAt = NOW), "device-b")),
                            nextCursor = "records-5",
                            hasMore = false,
                        ),
                    )

                val outcome = engine().sync(SyncTrigger.SCHEDULED)

                assertEquals(
                    SyncOutcome.Synced(
                        pushed = 0,
                        applied = 1,
                        recordsPulled = 1,
                        recordCursorAdvanced = true,
                    ),
                    outcome,
                )
                assertEquals(listOf<String?>("records-4"), transport.requestedDocumentCursors)
                assertEquals("records-5", store.documentCursor)
                assertEquals(listOf(NOW), store.documentReceivedAt)
            }

        @Test
        fun `a failed record pull fails the run`() =
            runTest {
                transport.documentPullFailure = SyncFailure("server is down")

                val outcome = engine().sync(SyncTrigger.MANUAL)

                assertInstanceOf(SyncOutcome.Failed::class.java, outcome)
                assertEquals("server is down", store.status.value.lastError)
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
    val documents = mutableMapOf<String, SyncDocument>()
    var documentCursor: String? = null
    val documentReceivedAt = mutableListOf<Instant>()
    var resets = 0
    private var nextSequence = 1L

    fun enqueueTask(task: StudyTask) {
        queue +=
            SyncQueueItem(
                sequence = nextSequence++,
                entityType = SyncEntityType.TASK,
                entityId = task.id,
                operation = SyncOperation.UPSERT,
                updatedAt = task.updatedAt,
                deviceId = "device-a",
            )
        documents[task.id] = SyncTaskRecord(task, "device-a")
        status.value = status.value.copy(pendingCount = queue.size)
    }

    fun enqueueMaterial(material: Material) {
        queue +=
            SyncQueueItem(
                sequence = nextSequence++,
                entityType = SyncEntityType.MATERIAL,
                entityId = material.id,
                operation = SyncOperation.UPSERT,
                updatedAt = material.updatedAt,
                deviceId = "device-a",
            )
        documents[material.id] = SyncMaterialRecord(material, "device-a")
        status.value = status.value.copy(pendingCount = queue.size)
    }

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

    override suspend fun documentRecord(item: SyncQueueItem): SyncDocument? = documents[item.entityId]

    override suspend fun applyDocumentPage(
        page: SyncDocumentPage,
        receivedAt: Instant,
    ): Int {
        documentReceivedAt += receivedAt
        page.changes.forEach { documents[it.id] = it }
        page.nextCursor?.let { documentCursor = it }
        return page.changes.size
    }

    override suspend fun documentCursor(): String? = documentCursor

    override suspend fun resetForAccountChange() {
        resets++
        cursor = null
        documentCursor = null
    }

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
    var documentPages: List<SyncDocumentPage> = emptyList()
    var documentPushFailure: SyncFailure? = null
    var documentPullFailure: SyncFailure? = null
    val pushedDocuments = mutableListOf<List<String>>()
    var documentPulls = 0
    val requestedDocumentCursors = mutableListOf<String?>()
    private var documentPageIndex = 0

    override suspend fun pushDocuments(documents: List<SyncDocument>): SyncResult<SyncPushAck> {
        documentPushFailure?.let { return SyncResult.Failure(it) }
        pushedDocuments += documents.map { it.id }
        return SyncResult.Success(SyncPushAck(acceptedIds = documents.map { it.id }.toSet()))
    }

    override suspend fun pullDocuments(
        cursor: String?,
        limit: Int,
    ): SyncResult<SyncDocumentPage> {
        documentPulls++
        requestedDocumentCursors += cursor
        documentPullFailure?.let { return SyncResult.Failure(it) }
        val page =
            documentPages.getOrNull(documentPageIndex)?.also { documentPageIndex++ }
                ?: SyncDocumentPage(changes = emptyList(), nextCursor = cursor, hasMore = false)
        return SyncResult.Success(page)
    }

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
