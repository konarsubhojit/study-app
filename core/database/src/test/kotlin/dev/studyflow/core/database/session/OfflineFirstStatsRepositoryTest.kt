package dev.studyflow.core.database.session

import androidx.room.Room
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.database.DATABASE_ROBOLECTRIC_SDK
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.entity.SessionEventEntity
import dev.studyflow.core.database.entity.SubjectEntity
import dev.studyflow.core.database.entity.asEntity
import dev.studyflow.core.domain.stats.StatsBucketSize
import dev.studyflow.core.domain.stats.StatsRange
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionEventType
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The acceptance criterion for issue #60 is "no rounding drift": these tests seed a session the
 * same way [dev.studyflow.core.database.session.OfflineFirstSessionHistoryRepositoryTest] does —
 * once via a manual override, once via a replayed event log — and check the aggregate matches a
 * hand-computed expectation exactly, plus that a bucket boundary crossing a DST transition still
 * lands each session on the calendar day it was actually studied on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [DATABASE_ROBOLECTRIC_SDK])
class OfflineFirstStatsRepositoryTest {
    private lateinit var database: StudyFlowDatabase
    private lateinit var repository: OfflineFirstStatsRepository

    private fun setUpWith(zone: TimeZone) {
        database =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StudyFlowDatabase::class.java)
                .build()
        repository = OfflineFirstStatsRepository(database.sessionDao(), TimeZoneProvider { zone })
        runBlocking {
            database.subjectDao().upsertAll(
                listOf(
                    SubjectEntity(id = "math", name = "Math", colorArgb = 0, archived = false),
                    SubjectEntity(id = "history", name = "History", colorArgb = 0, archived = false),
                ),
            )
        }
    }

    @Before
    fun setUp() {
        setUpWith(TimeZone.UTC)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a manually overridden session contributes its override total exactly`() =
        runBlocking {
            seedOverriddenSession("session-1", subjectId = "math", start = EPOCH, counted = 47.minutes)

            val breakdown = repository.observeSubjectBreakdown(rangeAround(EPOCH)).first()

            assertEquals(47.minutes, breakdown.single { it.subjectId == "math" }.totalCounted)
        }

    @Test
    fun `a session derived from its event log reconciles exactly with a hand-computed total`() =
        runBlocking {
            // STARTED at +0, PAUSED at +20m (20m settled), RESUMED at +30m, STOPPED at +45m (15m
            // settled) -- 35m total, computed by TimerEngine's own rule (monotonic delta, same boot).
            seedEventDerivedSession(
                id = "session-2",
                subjectId = "math",
                start = EPOCH,
                events =
                    listOf(
                        SessionEventType.STARTED to 0.minutes,
                        SessionEventType.PAUSED to 20.minutes,
                        SessionEventType.RESUMED to 30.minutes,
                        SessionEventType.STOPPED to 45.minutes,
                    ),
            )

            val average = repository.observeAverageSessionLength(rangeAround(EPOCH)).first()

            assertEquals(35.minutes, average.average)
            assertEquals(1, average.sessionCount)
        }

    @Test
    fun `deleted and open sessions never contribute to a total`() =
        runBlocking {
            seedOverriddenSession("deleted", subjectId = "math", start = EPOCH, counted = 60.minutes, deleted = true)
            seedRunningSession("open", subjectId = "math", start = EPOCH)
            seedOverriddenSession("closed", subjectId = "math", start = EPOCH, counted = 10.minutes)

            val average = repository.observeAverageSessionLength(rangeAround(EPOCH)).first()

            assertEquals("only the one stopped, non-deleted session counts", 10.minutes, average.average)
            assertEquals(1, average.sessionCount)
        }

    @Test
    fun `daily buckets split at local midnight across a DST transition`() =
        runBlocking {
            setUpWith(TimeZone.of("America/New_York"))
            // US spring-forward 2026: clocks jump from 2 AM to 3 AM on March 8th.
            // 2026-03-08T06:30:00Z is 01:30 EST (before the jump, still March 7th local wall time... )
            // Use two sessions either side of local midnight rather than the jump itself, since the
            // acceptance criterion is that the *day* bucketing is DST-safe, not the jump's duration.
            val beforeMidnight = Instant.parse("2026-03-08T04:30:00Z") // 2026-03-07 23:30 EST
            val afterMidnight = Instant.parse("2026-03-08T06:30:00Z") // 2026-03-08 01:30 EST
            seedOverriddenSession("s1", subjectId = "math", start = beforeMidnight, counted = 10.minutes)
            seedOverriddenSession("s2", subjectId = "math", start = afterMidnight, counted = 20.minutes)

            val buckets =
                repository
                    .observeBucketTotals(
                        StatsRange(from = beforeMidnight - 1.minutes, to = afterMidnight + 1.minutes),
                        StatsBucketSize.DAY,
                    ).first()

            assertEquals("the two sessions fall on different local calendar days", 2, buckets.size)
            assertTrue(buckets.all { it.totalCounted > kotlin.time.Duration.ZERO })
        }

    private fun rangeAround(instant: Instant): StatsRange =
        StatsRange(from = instant - 1.minutes, to = instant + 1.hours)

    private suspend fun seedOverriddenSession(
        id: String,
        subjectId: String?,
        start: Instant,
        counted: kotlin.time.Duration,
        deleted: Boolean = false,
    ) {
        val session =
            StudySession(
                id = id,
                subjectId = subjectId,
                note = null,
                startedAt = start,
                endedAt = start + counted,
                status = SessionStatus.STOPPED,
                elapsed = SessionElapsed(counted = counted),
                deviceId = DEVICE_ID,
                updatedAt = start,
                manualOverride = true,
                deleted = deleted,
            )
        database.sessionDao().upsertSessions(listOf(session.asEntity()))
    }

    private suspend fun seedRunningSession(
        id: String,
        subjectId: String?,
        start: Instant,
    ) {
        val session =
            StudySession(
                id = id,
                subjectId = subjectId,
                note = null,
                startedAt = start,
                endedAt = null,
                status = SessionStatus.RUNNING,
                elapsed = SessionElapsed.ZERO,
                deviceId = DEVICE_ID,
                updatedAt = start,
                manualOverride = true,
            )
        database.sessionDao().upsertSessions(listOf(session.asEntity()))
    }

    /** Seeds a session purely from its event log, so the reducer — not an override — derives its total. */
    private suspend fun seedEventDerivedSession(
        id: String,
        subjectId: String?,
        start: Instant,
        events: List<Pair<SessionEventType, kotlin.time.Duration>>,
    ) {
        val lastEvent = events.last()
        val session =
            StudySession(
                id = id,
                subjectId = subjectId,
                note = null,
                startedAt = start,
                endedAt = if (lastEvent.first == SessionEventType.STOPPED) start + lastEvent.second else null,
                status =
                    if (lastEvent.first == SessionEventType.STOPPED) SessionStatus.STOPPED else SessionStatus.PAUSED,
                elapsed = SessionElapsed.ZERO,
                deviceId = DEVICE_ID,
                updatedAt = start,
                manualOverride = false,
            )
        database.sessionDao().upsertSessions(listOf(session.asEntity()))
        database.sessionDao().insertEvents(
            events.mapIndexed { sequence, (type, offset) ->
                SessionEventEntity(
                    id = "$id-event-$sequence",
                    sessionId = id,
                    type = type,
                    uptime = offset,
                    wallClock = start + offset,
                    bootId = BootId("boot-0"),
                    sequence = sequence.toLong(),
                )
            },
        )
    }

    private companion object {
        val EPOCH: Instant = Instant.parse("2026-03-01T09:00:00Z")
        const val DEVICE_ID = "device-1"
    }
}
