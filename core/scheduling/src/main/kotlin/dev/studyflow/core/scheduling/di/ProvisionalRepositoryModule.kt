package dev.studyflow.core.scheduling.di

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
import dev.studyflow.core.database.MissingMigrationPolicy
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.StudyFlowDatabaseFactory
import dev.studyflow.core.database.dao.SessionDao
import dev.studyflow.core.database.dao.StudyTaskDao
import dev.studyflow.core.database.dao.SubjectDao
import dev.studyflow.core.database.repository.OfflineFirstSubjectRepository
import dev.studyflow.core.database.repository.OfflineFirstTaskRepository
import dev.studyflow.core.database.session.OfflineFirstSessionRepository
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.subjects.SubjectRepository
import dev.studyflow.core.domain.tasks.TaskRepository
import javax.inject.Singleton

/**
 * Binds the Room database and its repositories, since no feature module has claimed that
 * responsibility yet.
 *
 * This is, for now, the only place [TaskRepository], [SubjectRepository] and [SessionRepository]
 * are bound — split out from [SchedulingModule] (which owns this feature's own services) purely so
 * that module does not also carry bindings that belong to the app's data layer. When a feature
 * module takes ownership of these repositories, this whole file should move there rather than
 * being duplicated — Hilt only tolerates one binding per type in the graph.
 */
@Module
@InstallIn(SingletonComponent::class)
public object ProvisionalRepositoryModule {
    @Provides
    @Singleton
    public fun database(
        @ApplicationContext context: Context,
    ): StudyFlowDatabase = StudyFlowDatabaseFactory.create(context, MissingMigrationPolicy.FAIL)

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
    ): SessionRepository = OfflineFirstSessionRepository(dao, deviceId(context))

    /** A per-install, per-device identifier; stable without needing a persisted UUID of our own. */
    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
}
