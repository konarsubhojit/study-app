package dev.studyflow.core.scheduling

import dev.studyflow.core.domain.sync.SyncResult
import dev.studyflow.core.domain.sync.SyncSessionRecord
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.SessionEventType
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.model.TimeAnchor
import dev.studyflow.core.network.model.SyncDeltaDto
import dev.studyflow.core.network.model.SyncSessionDto
import dev.studyflow.core.network.model.SyncSessionEventDto
import dev.studyflow.core.testing.logging.RecordingAppLogger
import dev.studyflow.core.testing.network.FakeStudyFlowBackend
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The wire half of sync, exercised through the real client against the in-memory backend.
 *
 * Going through [FakeStudyFlowBackend] rather than a stubbed `StudyFlowApi` is deliberate: it is
 * the serialization that these mappings can get wrong, and a hand-written stub would happily agree
 * with a DTO the server could never produce.
 */
class ApiSyncTransportTest {
    private val backend = FakeStudyFlowBackend()
    private val logger = RecordingAppLogger()

    private fun transport(): ApiSyncTransport =
        ApiSyncTransport(api = backend.api(), deviceIdProvider = { DEVICE }, logger = logger)

    @Test
    fun `a pushed session carries its projection and its whole event log`() =
        runTest {
            val result = transport().push(listOf(record()))

            assertTrue(result is SyncResult.Success)
            val push = backend.pushedSyncChanges.single()
            assertEquals(DEVICE, push.deviceId)
            val sent = push.changes.single()
            assertEquals(SESSION_ID, sent.id)
            assertEquals("STOPPED", sent.status)
            assertEquals(START.toString(), sent.startedAtIso)
            assertEquals(START.plus(25.minutes).toString(), sent.endedAtIso)
            assertEquals(1_500_000L, sent.countedMillis)
            assertFalse(sent.deleted)
            assertEquals(listOf("event-1", "event-2"), sent.events.map { it.id })
            assertEquals("STARTED", sent.events.first().type)
        }

    @Test
    fun `a tombstone is pushed as a deleted session rather than as an absence`() =
        runTest {
            val deleted = record().let { it.copy(session = it.session.copy(deleted = true)) }

            transport().push(listOf(deleted))

            assertTrue(
                backend.pushedSyncChanges
                    .single()
                    .changes
                    .single()
                    .deleted,
            )
        }

    @Test
    fun `a rejected id comes back separated from the accepted ones`() =
        runTest {
            backend.acceptedSyncIds = emptySet()

            val result = transport().push(listOf(record()))

            val ack = (result as SyncResult.Success).value
            assertEquals(emptySet<String>(), ack.acceptedIds)
            assertEquals(setOf(SESSION_ID), ack.rejectedIds)
        }

    @Test
    fun `a pulled page becomes domain records, cursor and all`() =
        runTest {
            backend.syncDelta =
                SyncDeltaDto(changes = listOf(dto()), nextCursor = "cursor-2", hasMore = true)

            val result = transport().pull(cursor = "cursor-1", limit = 50)

            val page = (result as SyncResult.Success).value
            assertEquals("cursor-2", page.nextCursor)
            assertTrue(page.hasMore)
            val record = page.changes.single()
            assertEquals(SESSION_ID, record.session.id)
            assertEquals(SessionStatus.STOPPED, record.session.status)
            assertEquals(START, record.session.startedAt)
            assertEquals(1_500_000.milliseconds, record.session.elapsed.counted)
            assertEquals(listOf(SessionEventType.STARTED), record.events.map { it.type })
        }

    @Test
    fun `an unreadable change is dropped and logged rather than wedging the page`() =
        runTest {
            backend.syncDelta =
                SyncDeltaDto(
                    changes = listOf(dto().copy(id = "broken", status = "TELEPORTING"), dto()),
                    nextCursor = "cursor-2",
                )

            val result = transport().pull(cursor = null, limit = 50)

            // The readable change still lands, and the cursor still moves: one row this version
            // cannot parse must not stop every later change from ever arriving.
            val page = (result as SyncResult.Success).value
            assertEquals(listOf(SESSION_ID), page.changes.map { it.session.id })
            assertEquals("cursor-2", page.nextCursor)
            assertTrue(logger.messages.any { it.message.contains("broken") })
        }

    @Test
    fun `a server error is retryable and a rejection is not`() =
        runTest {
            backend.failWith = HttpStatusCode.ServiceUnavailable
            val serverError = transport().pull(cursor = null, limit = 50)
            assertTrue((serverError as SyncResult.Failure).failure.retryable)

            backend.failWith = HttpStatusCode.Forbidden
            val rejected = transport().push(listOf(record()))
            assertFalse((rejected as SyncResult.Failure).failure.retryable)
        }

    private fun record(): SyncSessionRecord =
        SyncSessionRecord(
            session =
                StudySession(
                    id = SESSION_ID,
                    subjectId = "subject-1",
                    taskId = null,
                    note = "algebra",
                    startedAt = START,
                    endedAt = START.plus(25.minutes),
                    status = SessionStatus.STOPPED,
                    elapsed = SessionElapsed(counted = 1_500_000.milliseconds),
                    deviceId = DEVICE,
                    updatedAt = START.plus(26.minutes),
                ),
            events =
                listOf(
                    event("event-1", SessionEventType.STARTED, sequence = 1),
                    event("event-2", SessionEventType.STOPPED, sequence = 2),
                ),
        )

    private fun event(
        id: String,
        type: SessionEventType,
        sequence: Long,
    ): SessionEvent =
        SessionEvent(
            id = id,
            sessionId = SESSION_ID,
            type = type,
            anchor = TimeAnchor(uptime = sequence.milliseconds, wallClock = START, bootId = BootId("boot-0")),
            sequence = sequence,
        )

    private fun dto(): SyncSessionDto =
        SyncSessionDto(
            id = SESSION_ID,
            deviceId = "device-b",
            updatedAtIso = START.plus(26.minutes).toString(),
            startedAtIso = START.toString(),
            endedAtIso = START.plus(25.minutes).toString(),
            status = "STOPPED",
            countedMillis = 1_500_000,
            events =
                listOf(
                    SyncSessionEventDto(
                        id = "event-1",
                        sessionId = SESSION_ID,
                        type = "STARTED",
                        sequence = 1,
                        wallClockIso = START.toString(),
                        uptimeMillis = 1,
                        bootId = "boot-0",
                    ),
                ),
        )

    private companion object {
        const val DEVICE = "device-a"
        const val SESSION_ID = "session-1"
        val START: Instant = Instant.parse("2026-03-01T09:00:00Z")
    }
}
