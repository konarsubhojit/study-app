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
import dev.studyflow.core.domain.materials.DurationExtractor
import dev.studyflow.core.domain.materials.ImportContentReader
import dev.studyflow.core.domain.materials.MaterialImporter
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.feature.materials.data.AndroidDurationExtractor
import dev.studyflow.feature.materials.data.AndroidImportContentReader
import java.io.File
import javax.inject.Singleton

/**
 * The materials catalogue and file-import pipeline, assembled once for the whole app (issue #37).
 *
 * The database itself is provided here rather than inside `:core:database`: every other
 * Context-dependent binding in this app (see [NotificationsModule]) is wired at this level too, so
 * swapping an adapter — or, eventually, giving the tasks and sessions repositories their own
 * bindings alongside this one — always means editing the same place.
 */
@Module
@InstallIn(SingletonComponent::class)
public object MaterialsModule {
    @Provides
    @Singleton
    public fun materialDao(database: StudyFlowDatabase): MaterialDao = database.materialDao()

    @Provides
    @Singleton
    public fun materialRepository(dao: MaterialDao): MaterialRepository = OfflineFirstMaterialRepository(dao)

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

    private const val MATERIALS_DIRECTORY_NAME = "materials"
}
