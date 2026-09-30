package dev.studyflow.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.app.logging.logExportEnvironment
import dev.studyflow.feature.settings.LogExportEnvironment
import javax.inject.Singleton

/**
 * The header of an exported log, for the mock flavour.
 *
 * There is deliberately no base URL in this flavour — `FakeStudyFlowBackend` performs no network
 * I/O — so the header says so rather than inventing a host.
 */
@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsModule {
    @Provides
    @Singleton
    fun logExportEnvironment(): LogExportEnvironment = logExportEnvironment(apiHost = "none (mock backend)")
}
