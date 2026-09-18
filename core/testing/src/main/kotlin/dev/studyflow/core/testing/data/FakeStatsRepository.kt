package dev.studyflow.core.testing.data

import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.domain.session.DailySubjectTotal
import dev.studyflow.core.domain.stats.AverageSessionLength
import dev.studyflow.core.domain.stats.BucketTotal
import dev.studyflow.core.domain.stats.HourOfDayTotal
import dev.studyflow.core.domain.stats.StatsAggregator
import dev.studyflow.core.domain.stats.StatsBucketSize
import dev.studyflow.core.domain.stats.StatsRange
import dev.studyflow.core.domain.stats.StatsRepository
import dev.studyflow.core.domain.stats.SubjectTotal
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [StatsRepository] for ViewModel tests.
 *
 * Reuses [StatsAggregator] — the same pure roll-up the Room-backed repository delegates to — so a
 * feature test exercises the real bucketing/grouping rules instead of a hand-rolled stand-in that
 * could silently drift from production behaviour. Only the storage and range/subject filtering are
 * faked, mirroring [FakeSessionHistoryRepository].
 */
public class FakeStatsRepository(
    private val timeZoneProvider: TimeZoneProvider,
    seed: List<StudySession> = emptyList(),
) : StatsRepository {
    private val sessions = MutableStateFlow(seed)

    public fun put(session: StudySession) {
        sessions.value = sessions.value.filterNot { it.id == session.id } + session
    }

    override fun observeBucketTotals(
        range: StatsRange,
        bucketSize: StatsBucketSize,
        subjectId: String?,
    ): Flow<List<BucketTotal>> =
        filtered(range, subjectId).map { StatsAggregator.bucketTotals(it, timeZoneProvider.current(), bucketSize) }

    override fun observeSubjectBreakdown(
        range: StatsRange,
        subjectId: String?,
    ): Flow<List<SubjectTotal>> = filtered(range, subjectId).map(StatsAggregator::subjectBreakdown)

    override fun observeHourOfDayHeatmap(
        range: StatsRange,
        subjectId: String?,
    ): Flow<List<HourOfDayTotal>> =
        filtered(range, subjectId).map { StatsAggregator.hourOfDayHeatmap(it, timeZoneProvider.current()) }

    override fun observeAverageSessionLength(
        range: StatsRange,
        subjectId: String?,
    ): Flow<AverageSessionLength> = filtered(range, subjectId).map(StatsAggregator::averageSessionLength)

    override fun observeDailySubjectTotals(
        range: StatsRange,
        subjectId: String?,
    ): Flow<List<DailySubjectTotal>> =
        filtered(range, subjectId).map { StatsAggregator.dailySubjectTotals(it, timeZoneProvider.current()) }

    private fun filtered(
        range: StatsRange,
        subjectId: String?,
    ): Flow<List<StudySession>> =
        sessions.map { all ->
            all.filter { session ->
                !session.deleted &&
                    session.status == SessionStatus.STOPPED &&
                    (subjectId == null || session.subjectId == subjectId) &&
                    session.startedAt >= range.from &&
                    session.startedAt < range.to
            }
        }
}
