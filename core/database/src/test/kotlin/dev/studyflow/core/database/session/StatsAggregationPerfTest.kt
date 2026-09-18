package dev.studyflow.core.database.session

import androidx.room.Room
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.database.DATABASE_ROBOLECTRIC_SDK
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.entity.SessionEventEntity
import dev.studyflow.core.database.entity.StudySessionEntity
import dev.studyflow.core.database.entity.SubjectEntity
import dev.studyflow.core.domain.stats.StatsBucketSize
import dev.studyflow.core.domain.stats.StatsRange
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.SessionEventType
import dev.studyflow.core.model.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The acceptance criterion for issue #60: five years of history renders the insights screen
 * "without a spinner". `:benchmark` has no Macrobenchmark harness set up yet (it is still the empty
 * scaffold noted in the issue), so this follows [dev.studyflow.core.database.DatabaseQueryPlanTest]'s
 * existing convention instead — an in-memory Room database seeded at realistic scale, timed on the
 * JVM. It is a looser bound than a device Macrobenchmark would give, but it catches the failure mode
 * that actually matters here: an aggregate that scans or replays every session in the *table*
 * instead of only the ones the query narrows to the requested range.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [DATABASE_ROBOLECTRIC_SDK])
class StatsAggregationPerfTest {
    private lateinit var database: StudyFlowDatabase
    private lateinit var repository: OfflineFirstStatsRepository

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StudyFlowDatabase::class.java)
                .build()
        repository = OfflineFirstStatsRepository(database.sessionDao(), TimeZoneProvider { TimeZone.UTC })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `aggregates five years of sessions well within a render budget`() =
        runBlocking {
            seedFiveYearsOfSessions()
            val range = StatsRange(from = BASE, to = BASE + (SESSION_COUNT * SPACING_MINUTES).minutes)

            val elapsedMs =
                measureTimeMillis {
                    repository.observeSubjectBreakdown(range).first()
                    repository.observeBucketTotals(range, StatsBucketSize.MONTH).first()
                    repository.observeHourOfDayHeatmap(range).first()
                    repository.observeAverageSessionLength(range).first()
                }

            assertTrue(
                "aggregating $SESSION_COUNT sessions took ${elapsedMs}ms, expected under ${BUDGET_MILLIS}ms",
                elapsedMs < BUDGET_MILLIS,
            )
        }

    /**
     * ~[SESSION_COUNT] sessions spread roughly every [SPACING_MINUTES] across five years, half
     * closed by a correction override (the cheap path) and half derived from a two-event log (the
     * path that has to replay [dev.studyflow.core.domain.session.SessionReducer]) — a realistic mix
     * rather than the fastest case for every row.
     */
    private suspend fun seedFiveYearsOfSessions() {
        val subjects = (0 until SUBJECT_COUNT).map { "subject-$it" }
        database.subjectDao().upsertAll(
            subjects.map { id -> SubjectEntity(id = id, name = id, colorArgb = 0, archived = false) },
        )

        val sessions = mutableListOf<StudySessionEntity>()
        val events = mutableListOf<SessionEventEntity>()
        repeat(SESSION_COUNT) { index ->
            val start = BASE + (index * SPACING_MINUTES).minutes
            val duration = (MIN_SESSION_MINUTES + (index % SESSION_MINUTES_VARIATION)).minutes
            val subjectId = subjects[index % SUBJECT_COUNT]
            val manualOverride = index % 2 == 0
            sessions +=
                StudySessionEntity(
                    id = "session-$index",
                    subjectId = subjectId,
                    note = null,
                    status = SessionStatus.STOPPED,
                    startedAt = start,
                    endedAt = start + duration,
                    deviceId = DEVICE_ID,
                    updatedAt = start,
                    manualOverride = manualOverride,
                    overrideCountedMillis = if (manualOverride) duration.inWholeMilliseconds else null,
                    overrideUnverifiedMillis = if (manualOverride) 0L else null,
                )
            if (!manualOverride) {
                events += sessionEvent("session-$index-event-0", "session-$index", SessionEventType.STARTED, start, 0)
                events +=
                    sessionEvent(
                        "session-$index-event-1",
                        "session-$index",
                        SessionEventType.STOPPED,
                        start + duration,
                        1,
                    )
            }
        }
        database.sessionDao().upsertSessions(sessions)
        database.sessionDao().insertEvents(events)
    }

    private fun sessionEvent(
        id: String,
        sessionId: String,
        type: SessionEventType,
        wallClock: Instant,
        sequence: Long,
    ): SessionEventEntity =
        SessionEventEntity(
            id = id,
            sessionId = sessionId,
            type = type,
            uptime = (wallClock - BASE),
            wallClock = wallClock,
            bootId = BootId("boot-0"),
            sequence = sequence,
        )

    private companion object {
        val BASE: Instant = Instant.parse("2021-01-01T00:00:00Z")
        const val DEVICE_ID = "device-1"
        const val SUBJECT_COUNT = 6
        const val SESSION_COUNT = 5_000
        const val SPACING_MINUTES = 525 // ~5 years / 5,000 sessions, a few sessions per day.
        const val MIN_SESSION_MINUTES = 20
        const val SESSION_MINUTES_VARIATION = 90
        const val BUDGET_MILLIS = 3_000L
    }
}
