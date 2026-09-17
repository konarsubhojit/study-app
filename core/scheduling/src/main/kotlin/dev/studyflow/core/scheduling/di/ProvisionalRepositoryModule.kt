package dev.studyflow.core.scheduling.di

import android.content.Context
import android.provider.Settings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.common.time.DeviceIdProvider
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.dao.SessionDao
import dev.studyflow.core.database.dao.SubjectDao
import dev.studyflow.core.database.repository.OfflineFirstSubjectRepository
import dev.studyflow.core.database.session.OfflineFirstSessionHistoryRepository
import dev.studyflow.core.database.session.OfflineFirstSessionRepository
import dev.studyflow.core.domain.session.SessionHistoryRepository
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.subjects.SubjectRepository
import javax.inject.Singleton

/**
 * Binds session and subject repositories, since no feature module has claimed that responsibility
 * yet.
 *
 * [SubjectRepository], [SessionRepository] and [SessionHistoryRepository] are bound here, while
 * the database, clock, time-zone provider, and task repository come from `:core:database`. It is
 * split out from [SchedulingModule], which owns this feature's own services.
 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions")
public object ProvisionalRepositoryModule {
    @Provides
    public fun subjectDao(database: StudyFlowDatabase): SubjectDao = database.subjectDao()

    @Provides
    public fun sessionDao(database: StudyFlowDatabase): SessionDao = database.sessionDao()

    @Provides
    @Singleton
    public fun deviceIdProvider(
        @ApplicationContext context: Context,
    ): DeviceIdProvider = DeviceIdProvider { deviceId(context) }

    @Provides
    @Singleton
    public fun subjectRepository(dao: SubjectDao): SubjectRepository = OfflineFirstSubjectRepository(dao)

    @Provides
    @Singleton
    public fun sessionRepository(
        dao: SessionDao,
        deviceIdProvider: DeviceIdProvider,
    ): SessionRepository = OfflineFirstSessionRepository(dao, deviceIdProvider.current())

    /**
     * The history screen's read/correct repository (issue #32) — kept alongside
     * [sessionRepository] rather than in `:feature:history` itself, for the same reason noted in
     * this module's own KDoc: no feature module owns the data layer yet.
     */
    @Provides
    @Singleton
    public fun sessionHistoryRepository(dao: SessionDao): SessionHistoryRepository =
        OfflineFirstSessionHistoryRepository(dao)

    /** A per-install, per-device identifier; stable without needing a persisted UUID of our own. */
    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
}
