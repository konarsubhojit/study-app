package dev.studyflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.studyflow.core.database.entity.SessionEventEntity
import dev.studyflow.core.database.entity.SessionWithEvents
import dev.studyflow.core.database.entity.StudySessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
public abstract class SessionDao {
    @Transaction
    @Query("SELECT * FROM study_sessions ORDER BY id ASC")
    public abstract fun observeAll(): Flow<List<SessionWithEvents>>

    @Query(
        """
        SELECT * FROM session_events
        WHERE session_id = :sessionId
        ORDER BY sequence ASC
        """,
    )
    public abstract fun observeEvents(sessionId: String): Flow<List<SessionEventEntity>>

    @Transaction
    public open suspend fun create(
        session: StudySessionEntity,
        initialEvent: SessionEventEntity,
    ) {
        require(initialEvent.sessionId == session.id) { "initial event belongs to a different session" }
        require(initialEvent.sequence == 0L) { "initial event must have sequence zero" }
        insertSession(session)
        insertEvent(initialEvent)
    }

    @Insert
    public abstract suspend fun appendEvent(event: SessionEventEntity)

    @Upsert
    public abstract suspend fun upsertSessions(sessions: List<StudySessionEntity>)

    @Insert
    public abstract suspend fun insertEvents(events: List<SessionEventEntity>)

    @Query("SELECT COUNT(*) FROM study_sessions")
    public abstract suspend fun count(): Int

    @Insert
    protected abstract suspend fun insertSession(session: StudySessionEntity)

    @Insert
    protected abstract suspend fun insertEvent(event: SessionEventEntity)
}
