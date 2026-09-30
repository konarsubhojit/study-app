package dev.studyflow.app.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.app.logging.AndroidAppLogger
import dev.studyflow.app.logging.FlaggedCrashReporter
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.CrashReporter
import dev.studyflow.core.common.logging.LogBuffer
import dev.studyflow.core.common.logging.RingLogBuffer
import dev.studyflow.core.common.time.SystemWallClock
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

    companion object {
        /**
         * One buffer for the process: the logger writes to it from every thread and the settings
         * export reads it, so a second instance would export a log nobody wrote to.
         */
        @Provides
        @Singleton
        fun logBuffer(): LogBuffer = RingLogBuffer(clock = SystemWallClock)
    }
}
