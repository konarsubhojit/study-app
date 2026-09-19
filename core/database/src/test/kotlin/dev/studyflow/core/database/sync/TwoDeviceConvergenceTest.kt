package dev.studyflow.core.database.sync

import androidx.room.Room
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.database.DATABASE_ROBOLECTRIC_SDK
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.session.OfflineFirstSessionHistoryRepository
import dev.studyflow.core.database.session.OfflineFirstSessionRepository
import dev.studyflow.core.domain.session.SessionCommandResult
import dev.studyflow.core.domain.sync.SessionSyncMerge
import dev.studyflow.core.domain.sync.SyncEngine
import dev.studyflow.core.domain.sync.SyncPage
import dev.studyflow.core.domain.sync.SyncPushAck
import dev.studyflow.core.domain.sync.SyncResult
import dev.studyflow.core.domain.sync.SyncSessionRecord
import dev.studyflow.core.domain.sync.SyncTransport
import dev.studyflow.core.domain.sync.SyncTrigger
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.testing.time.FakeDevice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Two real databases, one server, and the arguments they have to settle (issue #55).
 *
 * Everything below the fake server is production code: Room, the real DAO transactions, the real
 * [SyncEngine] and the real merge rules. Only the server is a stand-in, because what these tests
 * are about is whether two devices *end up agreeing* — a property no single-device test can show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [DATABASE_ROBOLECTRIC_SDK])
class TwoDeviceConvergenceTest {
    private val server = FakeSyncServer()
    private lateinit var deviceA: Device
    private lateinit var deviceB: Device

    @Before
    fun setUp() {
        deviceA = Device("device-a", server)
        deviceB = Device("device-b", server)
    }

    @After
    fun tearDown() {
        deviceA.close()
        deviceB.close()
    }

    @Test
    fun `a session recorded on one device arrives complete on the other`() =
        runBlocking {
            val recorded = deviceA.recordSession()

            deviceA.sync()
            deviceB.sync()

            val onB = requireNotNull(deviceB.session())
            assertEquals(recorded.id, onB.id)
            assertEquals(recorded.startedAt, onB.startedAt)
            assertEquals(recorded.elapsed.counted, onB.elapsed.counted)
            assertEquals(SessionStatus.STOPPED, onB.status)
            assertEquals(deviceA.eventIds(), deviceB.eventIds())
        }

    @Test
    fun `both devices converge on the same state after editing the same session`() =
        runBlocking {
            deviceA.recordSession()
            deviceA.sync()
            deviceB.sync()

            // Both edit while the other has not heard about it yet; B's edit is the later one.
            deviceA.editNote("noted on A", at = T1)
            deviceB.editNote("noted on B", at = T2)

            // Whatever order they reach the server in, and however many runs it takes, the two
            // devices have to end up byte-for-byte equal — that is what convergence means.
            deviceB.sync()
            deviceA.sync()
            deviceB.sync()

            assertEquals("noted on B", deviceA.session()?.note)
            assertEquals(deviceA.session(), deviceB.session())
            assertEquals(deviceA.eventIds(), deviceB.eventIds())
            assertTrue(deviceA.pendingCount() == 0 && deviceB.pendingCount() == 0)
        }

    @Test
    fun `an active session stays on the device that is running it`() =
        runBlocking {
            deviceA.startSession()

            deviceA.sync()
            deviceB.sync()

            assertNull(deviceB.session())
            assertEquals(0, server.recordCount())
            // And the device that owns it still has it, still running.
            assertEquals(SessionStatus.RUNNING, deviceA.session()?.status)
        }

    /**
     * The three-way case the acceptance criteria name, with its outcome written down.
     *
     * **Setup.** Both devices hold the same finished session. Device A goes offline and edits the
     * note at 12:01. Device B edits the same note at 12:02 and syncs. A third client (a browser,
     * say) deletes the session at 12:03. Device A then comes back online.
     *
     * **Outcome.** The deletion wins, on both devices and on the server: it is the newest write,
     * and last-write-wins compares `updatedAt` first. A's offline edit is *discarded* rather than
     * applied — when A finally pushes it, the server refuses it as older than the tombstone, and
     * the delta A pulls in the same run replaces A's row with the tombstone. The session is not
     * resurrected on either device, and the event log survives on both, so the deletion can still
     * be undone from history rather than having destroyed the measurement.
     *
     * **Why this and not "the edit wins".** An edit made offline carries the time it was made, not
     * the time it arrived; treating arrival as recency would let a phone that was in a drawer for
     * a week overwrite a week of newer decisions the moment it woke up.
     */
    @Test
    fun `an offline edit loses to a later server-side delete and does not resurrect the session`() =
        runBlocking {
            deviceA.recordSession()
            deviceA.sync()
            deviceB.sync()

            deviceA.editNote("edited offline on A", at = T1)
            deviceB.editNote("edited on B", at = T2)
            deviceB.sync()
            server.delete(SESSION_ID, at = T3, by = "web-client")

            deviceA.sync()
            deviceB.sync()

            assertTrue("A must not keep a live copy", deviceA.session()?.deleted == true)
            assertTrue("B must not keep a live copy", deviceB.session()?.deleted == true)
            assertTrue("the server must not be talked out of the delete", server.record(SESSION_ID).session.deleted)
            assertEquals("edited on B", deviceA.session()?.note)
            assertEquals(deviceA.session(), deviceB.session())
            // The stale edit is gone from A's queue: leaving it there would re-send it forever,
            // asking the server to undo a deletion the user already made.
            assertEquals(0, deviceA.pendingCount())
            // The log is intact, so the deletion is reversible rather than destructive.
            assertEquals(deviceA.eventIds(), deviceB.eventIds())
            assertFalse(deviceA.eventIds().isEmpty())
        }

    /**
     * The other side of the same rule, so its cost is explicit rather than discovered later.
     *
     * A tombstone is not permanent: a *newer* write undoes it, on every device. That is what makes
     * "undo delete" work across devices at all — and it is only reachable through a deliberate
     * restore, because the app itself refuses to edit a session that is deleted.
     */
    @Test
    fun `a restore written after a delete brings the session back everywhere`() =
        runBlocking {
            deviceA.recordSession()
            deviceA.sync()
            deviceB.sync()
            server.delete(SESSION_ID, at = T1, by = "web-client")
            deviceA.sync()
            deviceB.sync()

            server.restore(SESSION_ID, at = T3, by = "web-client", note = "restored from the web")
            deviceA.sync()
            deviceB.sync()

            assertEquals(false, deviceA.session()?.deleted)
            assertEquals("restored from the web", deviceB.session()?.note)
            assertEquals(deviceA.session(), deviceB.session())
        }

    @Test
    fun `a device that is already in step sends and stores nothing`() =
        runBlocking {
            deviceA.recordSession()
            deviceA.sync()
            server.resetCounters()

            deviceA.sync(SyncTrigger.OUTBOUND)

            assertEquals(0, server.pushes)
            assertEquals(0, server.pulls)

            // A scheduled run still asks — that is the protocol's minimum for "anything new?" —
            // but the answer carries no changes and nothing is written.
            deviceA.sync(SyncTrigger.SCHEDULED)
            assertEquals(1, server.pulls)
            assertEquals(0, server.pushes)
        }

    /** One device: a real database, a real store, a real engine, pointed at the shared server. */
    private class Device(
        val deviceId: String,
        server: FakeSyncServer,
    ) {
        val clock = FakeDevice()
        private val database =
            Room
                .inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    StudyFlowDatabase::class.java,
                ).build()
        private val sessions = OfflineFirstSessionRepository(database.sessionDao(), deviceId)
        private val history = OfflineFirstSessionHistoryRepository(database.sessionDao())
        private val store = RoomSyncStore(database.syncDao())
        private val engine =
            SyncEngine(
                store = store,
                transport = server,
                clock = Clock { clock.now() },
                batchSize = 2,
            )

        suspend fun startSession(): StudySession =
            sessions
                .execute(
                    TimerCommand.Start(SESSION_ID, subjectId = null, note = "Algebra"),
                    "$deviceId-event-start",
                    clock.anchor(),
                ).applied()
                .session

        suspend fun recordSession(): StudySession {
            startSession()
            clock.advance(25.minutes)
            return sessions
                .execute(TimerCommand.Stop, "$deviceId-event-stop", clock.anchor())
                .applied()
                .session
        }

        suspend fun editNote(
            note: String,
            at: Instant,
        ) {
            history.editNote(SESSION_ID, note, "$deviceId-correction-$at", at)
        }

        suspend fun sync(trigger: SyncTrigger = SyncTrigger.MANUAL) {
            engine.sync(trigger)
        }

        suspend fun session(): StudySession? = store.sessionRecord(SESSION_ID)?.session

        suspend fun eventIds(): List<String> =
            store
                .sessionRecord(SESSION_ID)
                ?.events
                ?.map { it.id }
                .orEmpty()

        suspend fun pendingCount(): Int = store.pending(limit = 10).size

        fun close() {
            database.close()
        }

        private fun SessionCommandResult.applied(): SessionCommandResult.Applied {
            assertTrue("expected an applied command but was $this", this is SessionCommandResult.Applied)
            return this as SessionCommandResult.Applied
        }
    }

    private companion object {
        const val SESSION_ID = "session-1"
        val T1: Instant = Instant.parse("2026-03-01T12:01:00Z")
        val T2: Instant = Instant.parse("2026-03-01T12:02:00Z")
        val T3: Instant = Instant.parse("2026-03-01T12:03:00Z")
    }
}

/**
 * A server that resolves changes the way the contract says it does, and remembers the order it
 * accepted them in so a cursor means something.
 *
 * It applies the *same* [SessionSyncMerge] rule the devices do — which is the point: the protocol
 * only converges if all three parties agree on who wins, and a fake that invented its own rule
 * would prove nothing about the real one.
 */
private class FakeSyncServer : SyncTransport {
    private val state = linkedMapOf<String, SyncSessionRecord>()
    private val log = mutableListOf<SyncSessionRecord>()
    var pushes: Int = 0
        private set
    var pulls: Int = 0
        private set

    fun recordCount(): Int = state.size

    fun record(id: String): SyncSessionRecord = requireNotNull(state[id]) { "no session $id on the server" }

    fun resetCounters() {
        pushes = 0
        pulls = 0
    }

    /** A deletion made by neither device — a browser tab, or another phone entirely. */
    fun delete(
        id: String,
        at: Instant,
        by: String,
    ): Unit = write(id, at, by) { it.copy(deleted = true) }

    /** The same third client changing its mind, which is how a cross-device undo reaches here. */
    fun restore(
        id: String,
        at: Instant,
        by: String,
        note: String,
    ): Unit = write(id, at, by) { it.copy(deleted = false, note = note) }

    private fun write(
        id: String,
        at: Instant,
        by: String,
        transform: (StudySession) -> StudySession,
    ) {
        val current = record(id)
        accept(current.copy(session = transform(current.session).copy(updatedAt = at, deviceId = by)))
    }

    override suspend fun push(records: List<SyncSessionRecord>): SyncResult<SyncPushAck> {
        pushes++
        val accepted = mutableSetOf<String>()
        val rejected = mutableSetOf<String>()
        records.forEach { record ->
            if (accept(record)) accepted += record.session.id else rejected += record.session.id
        }
        return SyncResult.Success(SyncPushAck(acceptedIds = accepted, rejectedIds = rejected))
    }

    override suspend fun pull(
        cursor: String?,
        limit: Int,
    ): SyncResult<SyncPage> {
        pulls++
        val from = cursor?.toIntOrNull() ?: 0
        val page = log.drop(from).take(limit)
        val next = from + page.size
        return SyncResult.Success(
            SyncPage(changes = page, nextCursor = next.toString(), hasMore = next < log.size),
        )
    }

    /** @return true when the change was stored, false when the server already holds something newer. */
    private fun accept(record: SyncSessionRecord): Boolean {
        if (!SessionSyncMerge.isSyncable(record.session)) return false
        val current = state[record.session.id]
        val merged =
            when {
                current == null -> {
                    record
                }

                SessionSyncMerge.wins(record.session, current.session) -> {
                    record.copy(events = SessionSyncMerge.mergeEvents(current.events, record.events))
                }

                else -> {
                    return false
                }
            }
        state[record.session.id] = merged
        log += merged
        return true
    }
}
