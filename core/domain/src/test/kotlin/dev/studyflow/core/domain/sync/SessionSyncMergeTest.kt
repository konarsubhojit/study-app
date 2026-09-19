package dev.studyflow.core.domain.sync

import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.testing.data.testSessionEvent
import dev.studyflow.core.testing.data.testStudySession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The conflict policy, which is the one part of sync that cannot be fixed by retrying.
 *
 * A lost push comes back on the next drain; a wrong merge silently destroys a user's study
 * record, so every rule gets its own test — including the ones that only matter when two devices
 * disagree at the same millisecond.
 */
@DisplayName("SessionSyncMerge")
class SessionSyncMergeTest {
    @Nested
    @DisplayName("what may be synced")
    inner class Syncable {
        @Test
        fun `a running session is never syncable`() {
            assertFalse(SessionSyncMerge.isSyncable(session(status = SessionStatus.RUNNING)))
        }

        @Test
        fun `a paused session is never syncable`() {
            assertFalse(SessionSyncMerge.isSyncable(session(status = SessionStatus.PAUSED)))
        }

        @Test
        fun `a stopped session is`() {
            assertTrue(SessionSyncMerge.isSyncable(session(status = SessionStatus.STOPPED)))
        }

        @Test
        fun `an inbound active session is refused rather than stored`() {
            val outcome =
                SessionSyncMerge.merge(
                    local = null,
                    remote = record(session(status = SessionStatus.RUNNING)),
                )

            assertInstanceOf(SessionMergeOutcome.Rejected::class.java, outcome)
        }
    }

    @Nested
    @DisplayName("last write wins")
    inner class LastWriteWins {
        @Test
        fun `the newer updatedAt wins`() {
            val local = record(session(note = "local", updatedAt = AT))
            val remote = record(session(note = "remote", updatedAt = AT + ONE_SECOND))

            val merged = SessionSyncMerge.merge(local, remote).merged()

            assertEquals("remote", merged.record.session.note)
            assertTrue(merged.remoteWon)
        }

        @Test
        fun `an older inbound change loses and is not stored`() {
            val local = record(session(note = "local", updatedAt = AT + ONE_SECOND))
            val remote = record(session(note = "remote", updatedAt = AT))

            val merged = SessionSyncMerge.merge(local, remote).merged()

            assertEquals("local", merged.record.session.note)
            assertFalse(merged.remoteWon)
        }

        @Test
        fun `at the same instant the higher device id wins, so both devices agree`() {
            val fromA = session(note = "from-a", deviceId = "device-a", updatedAt = AT)
            val fromB = session(note = "from-b", deviceId = "device-b", updatedAt = AT)

            // The same pair, merged from either side, has to produce the same winner — otherwise
            // the two devices would settle on different states and stay that way forever.
            val onDeviceA = SessionSyncMerge.merge(record(fromA), record(fromB)).merged()
            val onDeviceB = SessionSyncMerge.merge(record(fromB), record(fromA)).merged()

            assertEquals("from-b", onDeviceA.record.session.note)
            assertEquals("from-b", onDeviceB.record.session.note)
        }

        @Test
        fun `a session this device has never seen is stored as is`() {
            val remote = record(session(note = "remote"))

            val merged = SessionSyncMerge.merge(local = null, remote = remote).merged()

            assertEquals("remote", merged.record.session.note)
            assertTrue(merged.remoteWon)
        }
    }

    @Nested
    @DisplayName("tombstones")
    inner class Tombstones {
        @Test
        fun `a delete replaces a live session recorded earlier`() {
            val local = record(session(updatedAt = AT))
            val remote = record(session(updatedAt = AT + ONE_SECOND, deleted = true))

            val merged = SessionSyncMerge.merge(local, remote).merged()

            assertTrue(merged.record.session.deleted)
        }

        @Test
        fun `a stale edit cannot resurrect a deleted session`() {
            val local = record(session(updatedAt = AT + ONE_SECOND, deleted = true))
            val remote = record(session(note = "edited", updatedAt = AT))

            val merged = SessionSyncMerge.merge(local, remote).merged()

            assertTrue(merged.record.session.deleted)
            assertFalse(merged.remoteWon)
        }

        @Test
        fun `a delete beats an edit written at the same instant on the same device`() {
            val local = record(session(note = "edited", updatedAt = AT))
            val remote = record(session(updatedAt = AT, deleted = true))

            val merged = SessionSyncMerge.merge(local, remote).merged()

            assertTrue(merged.record.session.deleted)
        }

        @Test
        fun `a tombstone keeps the event log, so the deletion itself survives a replay`() {
            val local = record(session(updatedAt = AT), events = listOf(event("event-1", sequence = 0)))
            val remote = record(session(updatedAt = AT + ONE_SECOND, deleted = true))

            val merged = SessionSyncMerge.merge(local, remote).merged()

            assertEquals(listOf("event-1"), merged.record.events.map { it.id })
        }
    }

    @Nested
    @DisplayName("the event log")
    inner class EventLog {
        @Test
        fun `logs are unioned by id`() {
            val local = listOf(event("event-1", sequence = 0), event("event-2", sequence = 1))
            val remote = listOf(event("event-2", sequence = 1), event("event-3", sequence = 2))

            val merged = SessionSyncMerge.mergeEvents(local, remote)

            assertEquals(listOf("event-1", "event-2", "event-3"), merged.map { it.id })
        }

        @Test
        fun `a redelivered event never overwrites the copy already stored`() {
            val local = listOf(event("event-1", sequence = 0))
            val tampered = listOf(event("event-1", sequence = 99))

            val merged = SessionSyncMerge.mergeEvents(local, tampered)

            assertEquals(listOf(0L), merged.map { it.sequence })
        }

        @Test
        fun `both devices order the merged log identically`() {
            val local = listOf(event("event-b", sequence = 1), event("event-a", sequence = 0))
            val remote = listOf(event("event-c", sequence = 1))

            val onOne = SessionSyncMerge.mergeEvents(local, remote).map { it.id }
            val onTwo = SessionSyncMerge.mergeEvents(remote, local).map { it.id }

            assertEquals(listOf("event-a", "event-b", "event-c"), onOne)
            assertEquals(onOne, onTwo)
        }

        @Test
        fun `the log is merged even when the local metadata wins`() {
            val local = record(session(updatedAt = AT + ONE_SECOND), events = listOf(event("event-1", sequence = 0)))
            val remote = record(session(updatedAt = AT), events = listOf(event("event-2", sequence = 1)))

            val merged = SessionSyncMerge.merge(local, remote).merged()

            // Losing the last-write-wins comparison is about *metadata*. An event the other device
            // recorded is still a fact, and dropping it would lose measured study time.
            assertEquals(listOf("event-1", "event-2"), merged.record.events.map { it.id })
        }
    }

    private fun SessionMergeOutcome.merged(): SessionMergeOutcome.Merged =
        assertInstanceOf(SessionMergeOutcome.Merged::class.java, this)

    private fun session(
        status: SessionStatus = SessionStatus.STOPPED,
        note: String? = null,
        deviceId: String = "device-a",
        updatedAt: Instant = AT,
        deleted: Boolean = false,
    ) = testStudySession(
        id = SESSION_ID,
        note = note,
        status = status,
        deviceId = deviceId,
        updatedAt = updatedAt,
        deleted = deleted,
    )

    private fun record(
        session: StudySession,
        events: List<SessionEvent> = emptyList(),
    ) = SyncSessionRecord(session = session, events = events)

    private fun event(
        id: String,
        sequence: Long,
    ) = testSessionEvent(id = id, sessionId = SESSION_ID, sequence = sequence)

    private companion object {
        const val SESSION_ID = "session-1"
        val AT: Instant = Instant.parse("2026-03-01T09:00:00Z")
        val ONE_SECOND = 1.seconds
    }
}
