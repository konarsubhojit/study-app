package dev.studyflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.studyflow.core.database.entity.SessionEventEntity
import dev.studyflow.core.database.entity.SessionWithEvents
import dev.studyflow.core.database.entity.StudySessionEntity
import dev.studyflow.core.model.SessionStatus
import kotlinx.coroutines.flow.Flow

/**
 * What "active" means, in one place.
 *
 * The status literal has to be spelled out for SQLite, so leaving the predicate duplicated across
 * queries would let three copies drift apart from each other and from [SessionStatus].
 */
private const val ACTIVE_ON_DEVICE = "device_id = :deviceId AND deleted = 0 AND status != 'STOPPED'"

@Dao
public abstract class SessionDao {
    @Transaction
    @Query("SELECT * FROM study_sessions ORDER BY id ASC")
    public abstract fun observeAll(): Flow<List<SessionWithEvents>>

    /**
     * The sessions that are running or paused on [deviceId].
     *
     * A list rather than a single row on purpose: "at most one" is an invariant enforced on write,
     * and a query that silently picked one of two rows would hide a violation instead of exposing
     * it.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM study_sessions
        WHERE $ACTIVE_ON_DEVICE
        ORDER BY started_at DESC, id ASC
        """,
    )
    public abstract fun observeActive(deviceId: String): Flow<List<SessionWithEvents>>

    @Transaction
    @Query(
        """
        SELECT * FROM study_sessions
        WHERE $ACTIVE_ON_DEVICE
        ORDER BY started_at DESC, id ASC
        """,
    )
    public abstract suspend fun active(deviceId: String): List<SessionWithEvents>

    @Transaction
    @Query("SELECT * FROM study_sessions WHERE id = :sessionId")
    public abstract fun observeSession(sessionId: String): Flow<SessionWithEvents?>

    @Query(
        """
        SELECT * FROM session_events
        WHERE session_id = :sessionId
        ORDER BY sequence ASC
        """,
    )
    public abstract fun observeEvents(sessionId: String): Flow<List<SessionEventEntity>>

    /**
     * Commits one event and the projection it implies, atomically.
     *
     * Both writes land or neither does, so a kill between them cannot leave a half-written command;
     * and because the projection is only ever derived from the log, a kill *after* the transaction
     * still replays to the same state. The guards re-check, inside the transaction, the two facts
     * the caller decided on outside it, so a concurrent writer cannot slip in a second active
     * session or an out-of-order event.
     *
     * @throws IllegalStateException if another session is already active on the same device.
     * @throws IllegalArgumentException if the event does not continue the session's log.
     */
    @Transaction
    public open suspend fun appendAndProject(
        session: StudySessionEntity,
        event: SessionEventEntity,
    ) {
        require(event.sessionId == session.id) { "event belongs to a different session" }
        val conflicting = activeSessionIds(session.deviceId).firstOrNull { it != session.id }
        check(conflicting == null || session.status == SessionStatus.STOPPED) {
            "device ${session.deviceId} already has an active session: $conflicting"
        }
        val expected = lastSequence(session.id)?.plus(1) ?: 0L
        require(event.sequence == expected) {
            "event sequence ${event.sequence} does not continue the log of ${session.id}, expected $expected"
        }
        upsertSession(session)
        insertEvent(event)
    }

    @Query(
        """
        SELECT id FROM study_sessions
        WHERE $ACTIVE_ON_DEVICE
        """,
    )
    public abstract suspend fun activeSessionIds(deviceId: String): List<String>

    @Query("SELECT MAX(sequence) FROM session_events WHERE session_id = :sessionId")
    public abstract suspend fun lastSequence(sessionId: String): Long?

    @Insert
    public abstract suspend fun appendEvent(event: SessionEventEntity)

    @Upsert
    public abstract suspend fun upsertSessions(sessions: List<StudySessionEntity>)

    @Insert
    public abstract suspend fun insertEvents(events: List<SessionEventEntity>)

    @Query("SELECT COUNT(*) FROM study_sessions")
    public abstract suspend fun count(): Int

    @Upsert
    protected abstract suspend fun upsertSession(session: StudySessionEntity)

    @Insert
    protected abstract suspend fun insertEvent(event: SessionEventEntity)
}
