package dev.studyflow.core.scheduling.di

import android.content.Context
import android.provider.Settings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.dao.SessionDao
import dev.studyflow.core.database.dao.SubjectDao
import dev.studyflow.core.database.repository.OfflineFirstSubjectRepository
import dev.studyflow.core.database.session.OfflineFirstSessionRepository
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.subjects.SubjectRepository
import javax.inject.Singleton

/**
 * Binds repositories not yet owned by a feature module.
 *
 * The shared Room database and task repository live in `:core:database`; this provisional module
 * only keeps the subject and session bindings required by scheduling until those repositories move
 * to their owning feature modules.
 */
@Module
@InstallIn(SingletonComponent::class)
public object ProvisionalRepositoryModule {
    @Provides
    public fun subjectDao(database: StudyFlowDatabase): SubjectDao = database.subjectDao()

    @Provides
    public fun sessionDao(database: StudyFlowDatabase): SessionDao = database.sessionDao()

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
