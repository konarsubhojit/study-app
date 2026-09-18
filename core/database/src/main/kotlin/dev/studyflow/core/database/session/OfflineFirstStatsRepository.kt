package dev.studyflow.core.database.session

import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.database.dao.SessionDao
import dev.studyflow.core.database.entity.asExternalModel
import dev.studyflow.core.domain.session.DailySubjectTotal
import dev.studyflow.core.domain.stats.AverageSessionLength
import dev.studyflow.core.domain.stats.BucketTotal
import dev.studyflow.core.domain.stats.HourOfDayTotal
import dev.studyflow.core.domain.stats.StatsAggregator
import dev.studyflow.core.domain.stats.StatsBucketSize
import dev.studyflow.core.domain.stats.StatsRange
import dev.studyflow.core.domain.stats.StatsRepository
import dev.studyflow.core.domain.stats.SubjectTotal
import dev.studyflow.core.model.StudySession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Room-backed [StatsRepository] (issue #60).
 *
 * SQL does the part it is good at — narrowing potentially years of rows down to the handful that
 * fall in the requested range and (optionally) subject, via [SessionDao.statsSessions]'s `WHERE`
 * clause, which is what an index actually helps with. [StatsAggregator] then does the two parts SQL
 * cannot: resolving each session's true elapsed time (an override read or an event-log replay,
 * exactly as [dev.studyflow.core.database.entity.asExternalModel] already does for the history
 * list) and bucketing by local calendar day/week/month/hour, which needs a DST-aware time-zone
 * conversion that SQLite's `strftime` cannot perform correctly. Grouping and summing the
 * already-resolved, already-filtered handful of rows is cheap regardless of how many rows exist
 * outside the requested range — the indexed query, not the group-by, is what keeps this off the
 * full table.
 */
public class OfflineFirstStatsRepository(
    private val dao: SessionDao,
    private val timeZoneProvider: TimeZoneProvider,
) : StatsRepository {
    override fun observeBucketTotals(
        range: StatsRange,
        bucketSize: StatsBucketSize,
        subjectId: String?,
    ): Flow<List<BucketTotal>> =
        sessionsIn(range, subjectId).map { sessions ->
            StatsAggregator.bucketTotals(sessions, timeZoneProvider.current(), bucketSize)
        }

    override fun observeSubjectBreakdown(
        range: StatsRange,
        subjectId: String?,
    ): Flow<List<SubjectTotal>> = sessionsIn(range, subjectId).map(StatsAggregator::subjectBreakdown)

    override fun observeHourOfDayHeatmap(
        range: StatsRange,
        subjectId: String?,
    ): Flow<List<HourOfDayTotal>> =
        sessionsIn(range, subjectId).map { sessions ->
            StatsAggregator.hourOfDayHeatmap(sessions, timeZoneProvider.current())
        }

    override fun observeAverageSessionLength(
        range: StatsRange,
        subjectId: String?,
    ): Flow<AverageSessionLength> = sessionsIn(range, subjectId).map(StatsAggregator::averageSessionLength)

    override fun observeDailySubjectTotals(
        range: StatsRange,
        subjectId: String?,
    ): Flow<List<DailySubjectTotal>> =
        sessionsIn(range, subjectId).map { sessions ->
            StatsAggregator.dailySubjectTotals(sessions, timeZoneProvider.current())
        }

    private fun sessionsIn(
        range: StatsRange,
        subjectId: String?,
    ): Flow<List<StudySession>> =
        dao.statsSessions(subjectId, range.from, range.to).map { rows -> rows.mapNotNull { it.asExternalModel() } }
}
