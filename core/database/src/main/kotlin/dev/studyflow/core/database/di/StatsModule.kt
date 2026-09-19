package dev.studyflow.core.database.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.database.dao.SessionDao
import dev.studyflow.core.database.session.OfflineFirstStatsRepository
import dev.studyflow.core.domain.stats.StatsRepository
import dev.studyflow.core.domain.stats.WeeklySummaryProvider
import javax.inject.Singleton

/**
 * Wires the SQL-driven statistics/insights aggregates into the app's Hilt graph (issue #60).
 *
 * Kept separate from [DatabaseModule] so that module stays focused on the database, task and
 * session-history bindings it already had; stats is its own concern with its own repository.
 */
@Module
@InstallIn(SingletonComponent::class)
public object StatsModule {
    @Provides
    @Singleton
    public fun statsRepository(
        dao: SessionDao,
        timeZoneProvider: TimeZoneProvider,
    ): StatsRepository = OfflineFirstStatsRepository(dao, timeZoneProvider)

    /**
     * The weekly recap reads the same aggregates as the insights screen (issue #63), so it is
     * bound next to them rather than next to the worker that happens to deliver it.
     */
    @Provides
    @Singleton
    public fun weeklySummaryProvider(
        statsRepository: StatsRepository,
        timeZoneProvider: TimeZoneProvider,
    ): WeeklySummaryProvider = WeeklySummaryProvider(statsRepository, timeZoneProvider)
}
