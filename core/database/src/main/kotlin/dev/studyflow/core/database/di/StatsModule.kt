package dev.studyflow.core.database.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.database.dao.SessionDao
import dev.studyflow.core.database.session.OfflineFirstStatsRepository
import dev.studyflow.core.domain.stats.StatsRepository
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
}
