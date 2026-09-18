package dev.studyflow.core.scheduling.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.common.time.AnchoredClock
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.DefaultAnchoredClock
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.dao.MaterialUploadPartDao
import dev.studyflow.core.database.repository.RoomUploadProgressStore
import dev.studyflow.core.datastore.UserSettingsStore
import dev.studyflow.core.datastore.userSettingsStore
import dev.studyflow.core.domain.materials.DownloadProgressStore
import dev.studyflow.core.domain.materials.MaterialDownloadCoordinator
import dev.studyflow.core.domain.materials.MaterialRepository
import dev.studyflow.core.domain.materials.MaterialUploadCoordinator
import dev.studyflow.core.domain.materials.UploadProgressStore
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.subjects.SubjectRepository
import dev.studyflow.core.domain.tasks.TaskRepository
import dev.studyflow.core.notifications.StudyFlowNotificationFactory
import dev.studyflow.core.notifications.StudyFlowNotifier
import dev.studyflow.core.scheduling.AndroidBootIdProvider
import dev.studyflow.core.scheduling.AndroidElapsedRealtimeSource
import dev.studyflow.core.scheduling.AndroidReminderPlatformScheduler
import dev.studyflow.core.scheduling.AndroidSchedulingCapabilitiesProvider
import dev.studyflow.core.scheduling.DownloadTransport
import dev.studyflow.core.scheduling.ReminderActionExecutor
import dev.studyflow.core.scheduling.ReminderDeliveryCoordinator
import dev.studyflow.core.scheduling.ReminderPlatformScheduler
import dev.studyflow.core.scheduling.ReminderSchedulingService
import dev.studyflow.core.scheduling.SchedulingCapabilitiesProvider
import dev.studyflow.core.scheduling.SharedPreferencesDownloadProgressStore
import dev.studyflow.core.scheduling.UrlConnectionDownloadTransport
import dev.studyflow.core.scheduling.WorkManagerMaterialDownloadCoordinator
import dev.studyflow.core.scheduling.WorkManagerMaterialUploadCoordinator
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

/**
 * Wires the services [ReminderDeliveryWorker][dev.studyflow.core.scheduling.ReminderDeliveryWorker],
 * [ReminderAlarmReceiver][dev.studyflow.core.scheduling.ReminderAlarmReceiver] and
 * [ReminderActionReceiver][dev.studyflow.core.scheduling.ReminderActionReceiver] need, following
 * `:core:storage`'s precedent of a core module owning its own Hilt module (docs/adr/0002).
 *
 * The repositories these services depend on ([TaskRepository], [SubjectRepository],
 * [SessionRepository]) are bound by `:core:database`, so this module stays about scheduling and
 * delivery only.
 *
 * [AnchoredClock] is bound here too: its real implementation needs [AndroidElapsedRealtimeSource]
 * and [AndroidBootIdProvider], both of which already live in this module for the same
 * "no Android-aware home yet" reason described on [AndroidElapsedRealtimeSource].
 */
@Module
@InstallIn(SingletonComponent::class)
public object SchedulingModule {
    @Provides
    @Singleton
    public fun schedulingCapabilitiesProvider(
        @ApplicationContext context: Context,
    ): SchedulingCapabilitiesProvider = AndroidSchedulingCapabilitiesProvider(context)

    @Provides
    @Singleton
    public fun reminderPlatformScheduler(
        @ApplicationContext context: Context,
    ): ReminderPlatformScheduler = AndroidReminderPlatformScheduler(context)

    @Provides
    @Singleton
    public fun anchoredClock(
        clock: Clock,
        bootIdProvider: AndroidBootIdProvider,
    ): AnchoredClock = DefaultAnchoredClock(clock, AndroidElapsedRealtimeSource, bootIdProvider)

    @Provides
    @Singleton
    public fun reminderSchedulingService(
        capabilitiesProvider: SchedulingCapabilitiesProvider,
        platformScheduler: ReminderPlatformScheduler,
        clock: Clock,
    ): ReminderSchedulingService = ReminderSchedulingService(capabilitiesProvider, platformScheduler) { clock.now() }

    @Provides
    @Singleton
    public fun userSettingsStore(
        @ApplicationContext context: Context,
    ): UserSettingsStore = context.userSettingsStore()

    @Provides
    @Singleton
    public fun reminderDeliveryCoordinator(
        @ApplicationContext context: Context,
        taskRepository: TaskRepository,
        subjectRepository: SubjectRepository,
        notifier: StudyFlowNotifier,
        notificationFactory: StudyFlowNotificationFactory,
        settingsStore: UserSettingsStore,
        capabilitiesProvider: SchedulingCapabilitiesProvider,
    ): ReminderDeliveryCoordinator =
        ReminderDeliveryCoordinator(
            context = context,
            taskRepository = taskRepository,
            subjectRepository = subjectRepository,
            notifier = notifier,
            notificationFactory = notificationFactory,
            digestEnabled = { settingsStore.data.first().digestEnabled },
            capabilitiesProvider = capabilitiesProvider,
        )

    @Provides
    @Singleton
    public fun reminderActionExecutor(
        @ApplicationContext context: Context,
        taskRepository: TaskRepository,
        schedulingService: ReminderSchedulingService,
        sessionRepository: SessionRepository,
        notifier: StudyFlowNotifier,
        deliveryCoordinator: ReminderDeliveryCoordinator,
        clock: Clock,
    ): ReminderActionExecutor =
        ReminderActionExecutor(
            context = context,
            taskRepository = taskRepository,
            schedulingService = schedulingService,
            sessionRepository = sessionRepository,
            notifier = notifier,
            deliveryCoordinator = deliveryCoordinator,
            wallClock = clock,
        )

    @Provides
    @Singleton
    public fun materialUploadPartDao(database: StudyFlowDatabase): MaterialUploadPartDao =
        database.materialUploadPartDao()

    @Provides
    @Singleton
    public fun uploadProgressStore(
        dao: MaterialUploadPartDao,
        clock: Clock,
    ): UploadProgressStore = RoomUploadProgressStore(dao, clock)

    @Provides
    @Singleton
    public fun materialUploadCoordinator(
        @ApplicationContext context: Context,
        materialRepository: MaterialRepository,
        settingsStore: UserSettingsStore,
    ): MaterialUploadCoordinator = WorkManagerMaterialUploadCoordinator(context, materialRepository, settingsStore)

    @Provides
    @Singleton
    public fun downloadProgressStore(
        @ApplicationContext context: Context,
    ): DownloadProgressStore =
        SharedPreferencesDownloadProgressStore(
            context.getSharedPreferences("studyflow-download-progress", Context.MODE_PRIVATE),
        )

    @Provides
    @Singleton
    public fun downloadTransport(): DownloadTransport = UrlConnectionDownloadTransport()

    @Provides
    @Singleton
    public fun materialDownloadCoordinator(
        @ApplicationContext context: Context,
    ): MaterialDownloadCoordinator = WorkManagerMaterialDownloadCoordinator(context)
}
