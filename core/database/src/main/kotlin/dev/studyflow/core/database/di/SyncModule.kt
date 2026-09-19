package dev.studyflow.core.database.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.dao.SyncDao
import dev.studyflow.core.database.sync.RoomSyncStore
import dev.studyflow.core.domain.sync.SyncStatusRepository
import dev.studyflow.core.domain.sync.SyncStore
import javax.inject.Singleton

/**
 * Binds the local half of the sync engine (issue #55).
 *
 * Split out of [DatabaseModule] for the same reason [DeviceIdModule] is: one module per concern
 * keeps each under detekt's function-count threshold and makes the graph readable. The same
 * instance is exposed twice — as [SyncStore] for the engine, and as the narrower
 * [SyncStatusRepository] a settings screen binds to — so the UI cannot accidentally drive a sync
 * by holding the full store.
 */
@Module
@InstallIn(SingletonComponent::class)
public object SyncModule {
    @Provides
    public fun syncDao(database: StudyFlowDatabase): SyncDao = database.syncDao()

    @Provides
    @Singleton
    public fun syncStore(dao: SyncDao): SyncStore = RoomSyncStore(dao)

    @Provides
    @Singleton
    public fun syncStatusRepository(store: SyncStore): SyncStatusRepository = store
}
