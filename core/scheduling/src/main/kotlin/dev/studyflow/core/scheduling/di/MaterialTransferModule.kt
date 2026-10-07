package dev.studyflow.core.scheduling.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.dao.MaterialUploadPartDao
import dev.studyflow.core.database.repository.RoomUploadProgressStore
import dev.studyflow.core.datastore.UserSettingsStore
import dev.studyflow.core.domain.materials.DownloadProgressStore
import dev.studyflow.core.domain.materials.MaterialDownloadCoordinator
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.domain.materials.MaterialUploadCoordinator
import dev.studyflow.core.domain.materials.UploadNetworkSettings
import dev.studyflow.core.domain.materials.UploadProgressStore
import dev.studyflow.core.domain.sync.SyncScheduler
import dev.studyflow.core.scheduling.DownloadTransport
import dev.studyflow.core.scheduling.MaterialRemoteVerifier
import dev.studyflow.core.scheduling.MaterialUploadGate
import dev.studyflow.core.scheduling.SharedPreferencesDownloadProgressStore
import dev.studyflow.core.scheduling.SharedPreferencesRemoteCopyLedger
import dev.studyflow.core.scheduling.UrlConnectionDownloadTransport
import dev.studyflow.core.scheduling.WorkManagerMaterialDownloadCoordinator
import dev.studyflow.core.scheduling.WorkManagerMaterialUploadCoordinator
import dev.studyflow.core.scheduling.WorkManagerUploadNetworkSettings
import dev.studyflow.core.storage.ObjectStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public object MaterialTransferModule {
    @Provides
    @Singleton
    public fun materialUploadPartDao(database: StudyFlowDatabase): MaterialUploadPartDao =
        database.materialUploadPartDao()

    @Provides
    @Singleton
    public fun uploadProgressStore(
        dao: MaterialUploadPartDao,
        clock: Clock,
    ): UploadProgressStore = RoomUploadProgressStore(dao, clock)

    @Provides
    @Singleton
    public fun workManagerMaterialUploadCoordinator(
        @ApplicationContext context: Context,
        materialRepository: MaterialRepository,
        settingsStore: UserSettingsStore,
        syncScheduler: SyncScheduler,
        logger: AppLogger,
    ): WorkManagerMaterialUploadCoordinator =
        WorkManagerMaterialUploadCoordinator(
            context = context,
            materialRepository = materialRepository,
            settingsStore = settingsStore,
            syncScheduler = syncScheduler,
            logger = logger,
        )

    @Provides
    @Singleton
    public fun materialUploadCoordinator(coordinator: WorkManagerMaterialUploadCoordinator): MaterialUploadCoordinator =
        coordinator

    @Provides
    @Singleton
    public fun uploadNetworkSettings(
        settingsStore: UserSettingsStore,
        coordinator: WorkManagerMaterialUploadCoordinator,
    ): UploadNetworkSettings = WorkManagerUploadNetworkSettings(settingsStore, coordinator)

    @Provides
    @Singleton
    public fun materialUploadGate(): MaterialUploadGate = MaterialUploadGate()

    @Provides
    @Singleton
    public fun materialRemoteVerifier(
        @ApplicationContext context: Context,
        materialRepository: MaterialRepository,
        objectStore: ObjectStore,
        uploadCoordinator: MaterialUploadCoordinator,
        logger: AppLogger,
    ): MaterialRemoteVerifier =
        MaterialRemoteVerifier(
            materialRepository = materialRepository,
            objectStore = objectStore,
            uploadCoordinator = uploadCoordinator,
            ledger =
                SharedPreferencesRemoteCopyLedger(
                    context.getSharedPreferences("studyflow-material-remote-copies", Context.MODE_PRIVATE),
                ),
            logger = logger,
        )

    @Provides
    @Singleton
    public fun downloadProgressStore(
        @ApplicationContext context: Context,
    ): DownloadProgressStore =
        SharedPreferencesDownloadProgressStore(
            context.getSharedPreferences("studyflow-download-progress", Context.MODE_PRIVATE),
        )

    @Provides
    @Singleton
    public fun downloadTransport(dispatcherProvider: DispatcherProvider): DownloadTransport =
        UrlConnectionDownloadTransport(dispatcherProvider)

    @Provides
    @Singleton
    public fun materialDownloadCoordinator(
        @ApplicationContext context: Context,
    ): MaterialDownloadCoordinator = WorkManagerMaterialDownloadCoordinator(context)
}
