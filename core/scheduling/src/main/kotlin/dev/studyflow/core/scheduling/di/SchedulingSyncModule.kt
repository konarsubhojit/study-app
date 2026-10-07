package dev.studyflow.core.scheduling.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.DeviceIdProvider
import dev.studyflow.core.domain.session.SessionCommandObserver
import dev.studyflow.core.domain.sync.SyncEngine
import dev.studyflow.core.domain.sync.SyncScheduler
import dev.studyflow.core.domain.sync.SyncStore
import dev.studyflow.core.domain.sync.SyncTransport
import dev.studyflow.core.network.StudyFlowApi
import dev.studyflow.core.scheduling.ApiSyncTransport
import dev.studyflow.core.scheduling.SyncOnSessionCommandObserver
import dev.studyflow.core.scheduling.WorkManagerSyncCoordinator
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public object SchedulingSyncModule {
    @Provides
    @Singleton
    public fun syncTransport(
        api: StudyFlowApi,
        deviceIdProvider: DeviceIdProvider,
        logger: AppLogger,
    ): SyncTransport = ApiSyncTransport(api, deviceIdProvider, logger)

    @Provides
    @Singleton
    public fun syncEngine(
        store: SyncStore,
        transport: SyncTransport,
        clock: Clock,
    ): SyncEngine = SyncEngine(store = store, transport = transport, clock = clock)

    @Provides
    @Singleton
    public fun syncCoordinator(
        @ApplicationContext context: Context,
    ): WorkManagerSyncCoordinator = WorkManagerSyncCoordinator(context)

    @Provides
    @Singleton
    public fun syncScheduler(coordinator: WorkManagerSyncCoordinator): SyncScheduler = coordinator

    /**
     * Drains the outbound queue as soon as a session is stopped, rather than waiting for the next
     * scheduled run — the change is already durable, so this only decides how quickly it travels.
     */
    @Provides
    @IntoSet
    @Singleton
    public fun syncOnSessionCommand(coordinator: WorkManagerSyncCoordinator): SessionCommandObserver =
        SyncOnSessionCommandObserver(coordinator)
}
