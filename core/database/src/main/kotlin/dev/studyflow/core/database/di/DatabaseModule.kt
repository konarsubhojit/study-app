package dev.studyflow.core.database.di

import android.content.Context
import android.provider.Settings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.SystemTimeZoneProvider
import dev.studyflow.core.common.time.SystemWallClock
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.StudyFlowDatabaseFactory
import dev.studyflow.core.database.dao.SessionDao
import dev.studyflow.core.database.dao.StudyTaskDao
import dev.studyflow.core.database.dao.SubjectDao
import dev.studyflow.core.database.repository.OfflineFirstSubjectRepository
import dev.studyflow.core.database.repository.OfflineFirstTaskRepository
import dev.studyflow.core.database.session.OfflineFirstSessionRepository
import dev.studyflow.core.domain.session.SessionCommandObserver
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.subjects.SubjectRepository
import dev.studyflow.core.domain.tasks.TaskRepository
import javax.inject.Singleton

/**
 * Wires the local database into the app's Hilt graph (issue #46).
 *
 * [Clock] and [TimeZoneProvider] are bound here rather than beside a single caller: they are the
 * narrowest module that currently needs a device-wide "now" and "here", and any future feature that
 * needs the same answer should depend on the interface and reuse this binding instead of reading
 * `System` directly, exactly as [OfflineFirstTaskRepository] itself does.
 */
@Module
@InstallIn(SingletonComponent::class)
public object DatabaseModule {
    @Provides
    @Singleton
    public fun studyFlowDatabase(
        @ApplicationContext context: Context,
    ): StudyFlowDatabase = StudyFlowDatabaseFactory.create(context)

    @Provides
    public fun studyTaskDao(database: StudyFlowDatabase): StudyTaskDao = database.studyTaskDao()

    @Provides
    public fun subjectDao(database: StudyFlowDatabase): SubjectDao = database.subjectDao()

    @Provides
    public fun sessionDao(database: StudyFlowDatabase): SessionDao = database.sessionDao()

    @Provides
    @Singleton
    public fun clock(): Clock = SystemWallClock

    @Provides
    @Singleton
    public fun timeZoneProvider(): TimeZoneProvider = SystemTimeZoneProvider

    @Provides
    @Singleton
    public fun taskRepository(
        dao: StudyTaskDao,
        clock: Clock,
        timeZoneProvider: TimeZoneProvider,
    ): TaskRepository = OfflineFirstTaskRepository(dao, clock, timeZoneProvider)

    @Provides
    @Singleton
    public fun subjectRepository(dao: SubjectDao): SubjectRepository = OfflineFirstSubjectRepository(dao)

    @Provides
    @Singleton
    public fun sessionRepository(
        dao: SessionDao,
        @ApplicationContext context: Context,
        observers: Set<@JvmSuppressWildcards SessionCommandObserver>,
    ): SessionRepository = OfflineFirstSessionRepository(dao, deviceId(context), observers)

    /** A per-install, per-device identifier; stable without needing a persisted UUID of our own. */
    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
}
