package dev.studyflow.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.app.logging.AndroidAppLogger
import dev.studyflow.app.logging.FlaggedCrashReporter
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.CrashReporter
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LoggingModule {
    @Binds
    @Singleton
    abstract fun appLogger(logger: AndroidAppLogger): AppLogger

    @Binds
    @Singleton
    abstract fun crashReporter(reporter: FlaggedCrashReporter): CrashReporter
}
