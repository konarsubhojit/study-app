package dev.studyflow.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.app.BuildConfig
import dev.studyflow.app.logging.hostOf
import dev.studyflow.app.logging.logExportEnvironment
import dev.studyflow.feature.settings.LogExportEnvironment
import javax.inject.Singleton

/** The header of an exported log, for a build that talks to a real backend. */
@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsModule {
    @Provides
    @Singleton
    fun logExportEnvironment(): LogExportEnvironment = logExportEnvironment(hostOf(BuildConfig.API_BASE_URL))
}
