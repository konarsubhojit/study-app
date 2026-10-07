package dev.studyflow.core.scheduling.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.datastore.UserSettingsStore
import dev.studyflow.core.datastore.UserSettingsWeeklySummaryDeliveryLog
import dev.studyflow.core.datastore.UserSettingsWeeklySummarySettings
import dev.studyflow.core.datastore.WeeklySummaryDeliveryLog
import dev.studyflow.core.datastore.WeeklySummarySettings
import dev.studyflow.core.domain.stats.WeeklySummaryProvider
import dev.studyflow.core.domain.stats.WeeklySummaryScheduling
import dev.studyflow.core.domain.subjects.SubjectRepository
import dev.studyflow.core.notifications.StudyFlowNotificationFactory
import dev.studyflow.core.notifications.StudyFlowNotifier
import dev.studyflow.core.scheduling.WeeklySummaryDelivery
import dev.studyflow.core.scheduling.WeeklySummaryScheduler
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public object WeeklySummaryModule {
    @Provides
    @Singleton
    public fun weeklySummarySettings(store: UserSettingsStore): WeeklySummarySettings =
        UserSettingsWeeklySummarySettings(store)

    @Provides
    @Singleton
    public fun weeklySummaryDeliveryLog(store: UserSettingsStore): WeeklySummaryDeliveryLog =
        UserSettingsWeeklySummaryDeliveryLog(store)

    @Provides
    @Singleton
    public fun weeklySummaryScheduling(
        @ApplicationContext context: Context,
        settings: WeeklySummarySettings,
        clock: Clock,
        timeZoneProvider: TimeZoneProvider,
    ): WeeklySummaryScheduling =
        WeeklySummaryScheduler(
            settings = settings,
            workManager = WorkManager.getInstance(context),
            clock = clock,
            timeZoneProvider = timeZoneProvider,
        )

    @Provides
    @Singleton
    @Suppress("LongParameterList")
    public fun weeklySummaryDelivery(
        @ApplicationContext context: Context,
        settings: WeeklySummarySettings,
        deliveryLog: WeeklySummaryDeliveryLog,
        summaryProvider: WeeklySummaryProvider,
        subjectRepository: SubjectRepository,
        notifier: StudyFlowNotifier,
        notificationFactory: StudyFlowNotificationFactory,
        clock: Clock,
    ): WeeklySummaryDelivery =
        WeeklySummaryDelivery(
            context = context,
            settings = settings,
            deliveryLog = deliveryLog,
            summaryProvider = summaryProvider,
            subjectRepository = subjectRepository,
            notifier = notifier,
            notificationFactory = notificationFactory,
            clock = clock,
        )
}
