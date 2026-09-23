package dev.studyflow.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.time.SystemWallClock
import dev.studyflow.core.database.lifecycle.RoomDataEraser
import dev.studyflow.core.database.lifecycle.RoomDataLifecycleStore
import dev.studyflow.core.datastore.lifecycle.ActiveTimerEraser
import dev.studyflow.core.datastore.lifecycle.SettingsEraser
import dev.studyflow.core.datastore.lifecycle.TokenEraser
import dev.studyflow.core.domain.lifecycle.AccountDeletionCoordinator
import dev.studyflow.core.domain.lifecycle.ArchiveSinkFactory
import dev.studyflow.core.domain.lifecycle.ArchiveSourceFactory
import dev.studyflow.core.domain.lifecycle.DataEraser
import dev.studyflow.core.domain.lifecycle.DataExporter
import dev.studyflow.core.domain.lifecycle.DataImporter
import dev.studyflow.core.domain.lifecycle.DirectoryEraser
import dev.studyflow.core.domain.lifecycle.LocalDataReader
import dev.studyflow.core.domain.lifecycle.LocalDataWriter
import dev.studyflow.core.domain.lifecycle.MaterialFileStore
import dev.studyflow.core.domain.lifecycle.RemoteAccountEraser
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailSpec
import dev.studyflow.core.network.StudyFlowApi
import dev.studyflow.core.network.auth.TokenStore
import dev.studyflow.core.scheduling.ApiAccountEraser
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.storage.lifecycle.ObjectStoreEraser
import dev.studyflow.core.storage.lifecycle.StoredObjectKeys
import dev.studyflow.feature.settings.ArchiveNaming
import dev.studyflow.feature.settings.data.FileMaterialFileStore
import dev.studyflow.feature.settings.data.SafZipArchiveSinkFactory
import dev.studyflow.feature.settings.data.SafZipArchiveSourceFactory
import java.io.File
import java.time.LocalDate
import javax.inject.Singleton

/**
 * Export, restore and account deletion, assembled once for the whole app (issue #78).
 *
 * The domain owns the rules and knows none of this: which database the data is read from, which
 * `ContentResolver` writes the archive, which directories a deletion has to empty. This module is
 * the single place those answers are given, which is also what makes the order of erasure a
 * reviewable list rather than something scattered across the layers that happen to own a store.
 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions") // One binding per platform seam; splitting the file would hide the order.
public object DataLifecycleModule {
    @Provides
    @Singleton
    public fun localDataReader(store: RoomDataLifecycleStore): LocalDataReader = store

    @Provides
    @Singleton
    public fun localDataWriter(store: RoomDataLifecycleStore): LocalDataWriter = store

    @Provides
    @Singleton
    public fun materialFileStore(
        @ApplicationContext context: Context,
    ): MaterialFileStore = FileMaterialFileStore(File(context.filesDir, MATERIALS_DIRECTORY_NAME))

    @Provides
    @Singleton
    public fun archiveSinkFactory(
        @ApplicationContext context: Context,
    ): ArchiveSinkFactory = SafZipArchiveSinkFactory(context)

    @Provides
    @Singleton
    public fun archiveSourceFactory(
        @ApplicationContext context: Context,
    ): ArchiveSourceFactory = SafZipArchiveSourceFactory(context)

    @Provides
    @Singleton
    public fun dataExporter(
        localData: LocalDataReader,
        materialFiles: MaterialFileStore,
        sinkFactory: ArchiveSinkFactory,
        dispatcherProvider: DispatcherProvider,
    ): DataExporter =
        DataExporter(
            localData = localData,
            materialFiles = materialFiles,
            sinkFactory = sinkFactory,
            clock = SystemWallClock,
            dispatcherProvider = dispatcherProvider,
        )

    @Provides
    @Singleton
    public fun dataImporter(
        sourceFactory: ArchiveSourceFactory,
        localData: LocalDataReader,
        writer: LocalDataWriter,
        materialFiles: MaterialFileStore,
        dispatcherProvider: DispatcherProvider,
    ): DataImporter =
        DataImporter(
            sourceFactory = sourceFactory,
            localData = localData,
            writer = writer,
            materialFiles = materialFiles,
            dispatcherProvider = dispatcherProvider,
        )

    @Provides
    @Singleton
    public fun remoteAccountEraser(
        api: StudyFlowApi,
        tokenStore: TokenStore,
    ): RemoteAccountEraser = ApiAccountEraser(api = api, tokenStore = tokenStore)

    /**
     * The keys the object store still holds for this user.
     *
     * Derived from the catalogue rather than listed by the server, because the device knows every
     * content hash it ever uploaded and a deletion should not depend on the server agreeing about
     * what it stored.
     */
    @Provides
    @Singleton
    public fun storedObjectKeys(localData: LocalDataReader): StoredObjectKeys =
        StoredObjectKeys {
            localData.readSnapshot().materials.flatMap { material ->
                listOf(
                    ObjectKey.ofMaterial(material.contentHash),
                    ObjectKey.ofThumbnail(material.contentHash, ThumbnailSpec.GRID),
                )
            }
        }

    /**
     * Everything an account deletion erases, in the order it is erased.
     *
     * The order is the contract [AccountDeletionCoordinator] documents: stored objects and rows
     * first, then the preferences that describe them, and the credentials last — they are what an
     * intermediate retry would need, so they are the last thing to go.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList")
    public fun dataErasers(
        @ApplicationContext context: Context,
        objectStore: ObjectStore,
        storedObjectKeys: StoredObjectKeys,
        database: RoomDataEraser,
        settings: SettingsEraser,
        activeTimer: ActiveTimerEraser,
        tokens: TokenEraser,
    ): List<@JvmSuppressWildcards DataEraser> =
        listOf(
            ObjectStoreEraser(objectStore, storedObjectKeys),
            database,
            DirectoryEraser("materials") { File(context.filesDir, MATERIALS_DIRECTORY_NAME) },
            DirectoryEraser("thumbnails") { File(context.cacheDir, THUMBNAILS_DIRECTORY_NAME) },
            DirectoryEraser("caches") { context.cacheDir },
            settings,
            activeTimer,
            tokens,
        )

    @Provides
    @Singleton
    public fun accountDeletionCoordinator(
        remote: RemoteAccountEraser,
        erasers: List<@JvmSuppressWildcards DataEraser>,
        dispatcherProvider: DispatcherProvider,
    ): AccountDeletionCoordinator =
        AccountDeletionCoordinator(remote = remote, erasers = erasers, dispatcherProvider = dispatcherProvider)

    /** `studyflow-export-2026-03-01.zip`: dated, so a user with several archives can tell them apart. */
    @Provides
    @Singleton
    public fun archiveNaming(): ArchiveNaming = ArchiveNaming { "studyflow-export-${LocalDate.now()}.zip" }

    private const val MATERIALS_DIRECTORY_NAME = "materials"
    private const val THUMBNAILS_DIRECTORY_NAME = "thumbnails"
}
