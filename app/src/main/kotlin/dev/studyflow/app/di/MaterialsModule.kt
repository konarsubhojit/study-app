package dev.studyflow.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.time.SystemWallClock
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.dao.MaterialDao
import dev.studyflow.core.database.repository.OfflineFirstMaterialRepository
import dev.studyflow.core.domain.materials.ArchiveReader
import dev.studyflow.core.domain.materials.DurationExtractor
import dev.studyflow.core.domain.materials.ImportContentReader
import dev.studyflow.core.domain.materials.MaterialImporter
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailCache
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailLoader
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailRenderer
import dev.studyflow.feature.materials.data.AndroidDurationExtractor
import dev.studyflow.feature.materials.data.AndroidImportContentReader
import dev.studyflow.feature.materials.data.AndroidThumbnailRenderer
import dev.studyflow.feature.materials.data.ArchiveEntryExtractor
import dev.studyflow.feature.materials.data.FileThumbnailCache
import java.io.File
import javax.inject.Singleton

/**
 * The materials catalogue and file-import pipeline, assembled once for the whole app (issue #37).
 *
 * The shared database is provided by `:core:database`; this module only exposes the material
 * catalogue adapter and Android file-import bindings.
 */
@Module
@InstallIn(SingletonComponent::class)
public object MaterialsModule {
    @Provides
    @Singleton
    public fun materialDao(database: StudyFlowDatabase): MaterialDao = database.materialDao()

    @Provides
    @Singleton
    public fun materialRepository(dao: MaterialDao): MaterialRepository =
        OfflineFirstMaterialRepository(dao, clock = SystemWallClock)

    @Provides
    @Singleton
    public fun importContentReader(
        @ApplicationContext context: Context,
    ): ImportContentReader = AndroidImportContentReader(context)

    @Provides
    @Singleton
    public fun durationExtractor(): DurationExtractor = AndroidDurationExtractor()

    @Provides
    @Singleton
    public fun materialImporter(
        contentReader: ImportContentReader,
        repository: MaterialRepository,
        durationExtractor: DurationExtractor,
        dispatcherProvider: DispatcherProvider,
        @ApplicationContext context: Context,
    ): MaterialImporter =
        MaterialImporter(
            contentReader = contentReader,
            repository = repository,
            // App-private, durable, and untouched by scoped-storage changes to shared collections —
            // exactly what an imported file needs to survive the source URI being revoked later.
            destinationDirectory = { File(context.filesDir, MATERIALS_DIRECTORY_NAME) },
            clock = SystemWallClock,
            dispatcherProvider = dispatcherProvider,
            durationExtractor = durationExtractor,
        )

    @Provides
    @Singleton
    public fun thumbnailRenderer(dispatcherProvider: DispatcherProvider): ThumbnailRenderer =
        AndroidThumbnailRenderer(dispatcherProvider)

    @Provides
    @Singleton
    public fun thumbnailCache(
        dispatcherProvider: DispatcherProvider,
        @ApplicationContext context: Context,
    ): ThumbnailCache =
        FileThumbnailCache(
            // `cacheDir`, not `filesDir`: every thumbnail can be rendered again from the original,
            // so the platform is welcome to reclaim them under storage pressure (issue #42).
            directory = { File(context.cacheDir, THUMBNAILS_DIRECTORY_NAME) },
            dispatcherProvider = dispatcherProvider,
            clock = SystemWallClock,
        )

    @Provides
    @Singleton
    public fun thumbnailLoader(
        cache: ThumbnailCache,
        renderer: ThumbnailRenderer,
        dispatcherProvider: DispatcherProvider,
    ): ThumbnailLoader = ThumbnailLoader(cache = cache, renderer = renderer, dispatcherProvider = dispatcherProvider)

    @Provides
    @Singleton
    public fun archiveEntryExtractor(
        dispatcherProvider: DispatcherProvider,
        @ApplicationContext context: Context,
    ): ArchiveEntryExtractor =
        ArchiveEntryExtractor(
            reader = ArchiveReader(dispatcherProvider),
            // A subfolder of `materials/`, never a material id directly: an import's destination
            // file already owns that name, and an extracted entry's destination directory must
            // not collide with it.
            archivesDirectory = { File(context.filesDir, "$MATERIALS_DIRECTORY_NAME/$ARCHIVES_DIRECTORY_NAME") },
        )

    private const val MATERIALS_DIRECTORY_NAME = "materials"
    private const val THUMBNAILS_DIRECTORY_NAME = "thumbnails"
    private const val ARCHIVES_DIRECTORY_NAME = "archives"
}
