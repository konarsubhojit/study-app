package dev.studyflow.app.di

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.studyflow.app.R
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.notifications.AndroidNotificationPermissionReader
import dev.studyflow.core.notifications.AndroidNotificationSettingsSource
import dev.studyflow.core.notifications.NotificationChannelRegistrar
import dev.studyflow.core.notifications.NotificationPermissionReader
import dev.studyflow.core.notifications.NotificationPermissionRequestLog
import dev.studyflow.core.notifications.NotificationSettingsSource
import dev.studyflow.core.notifications.SharedPreferencesNotificationPermissionRequestLog
import dev.studyflow.core.notifications.StudyFlowNotificationFactory
import dev.studyflow.core.notifications.StudyFlowNotifier
import javax.inject.Singleton

/**
 * The notification layer, assembled once for the whole app (issue #24).
 *
 * The permission reader provided here never prompts: it has no activity, so
 * `shouldShowRationale` stays `false` and the state it reports is the one a service or a worker
 * can act on. The settings screen builds its own activity-aware view of the same data.
 */
@Module
@InstallIn(SingletonComponent::class)
object NotificationsModule {
    @Provides
    @Singleton
    fun requestLog(
        @ApplicationContext context: Context,
    ): NotificationPermissionRequestLog = SharedPreferencesNotificationPermissionRequestLog(context)

    @Provides
    @Singleton
    fun channelRegistrar(
        @ApplicationContext context: Context,
    ): NotificationChannelRegistrar = NotificationChannelRegistrar(context)

    @Provides
    @Singleton
    fun permissionReader(
        @ApplicationContext context: Context,
        requestLog: NotificationPermissionRequestLog,
    ): NotificationPermissionReader = AndroidNotificationPermissionReader(context = context, requestLog = requestLog)

    @Provides
    @Singleton
    fun notificationSettingsSource(
        @ApplicationContext context: Context,
        registrar: NotificationChannelRegistrar,
        requestLog: NotificationPermissionRequestLog,
    ): NotificationSettingsSource =
        AndroidNotificationSettingsSource(
            context = context,
            registrar = registrar,
            requestLog = requestLog,
        )

    @Provides
    @Singleton
    fun notificationFactory(
        @ApplicationContext context: Context,
    ): StudyFlowNotificationFactory = StudyFlowNotificationFactory(context, R.drawable.ic_notification)

    @Provides
    @Singleton
    fun notifier(
        @ApplicationContext context: Context,
        registrar: NotificationChannelRegistrar,
        permissions: NotificationPermissionReader,
        logger: AppLogger,
    ): StudyFlowNotifier =
        StudyFlowNotifier(
            registrar = registrar,
            permissions = permissions,
            manager = NotificationManagerCompat.from(context),
            logger = logger,
        )
}
