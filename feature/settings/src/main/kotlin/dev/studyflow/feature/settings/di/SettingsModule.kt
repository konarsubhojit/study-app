package dev.studyflow.feature.settings.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.LogBuffer
import dev.studyflow.core.common.time.SystemWallClock
import dev.studyflow.core.datastore.AlarmRingtoneSettings
import dev.studyflow.core.datastore.UserSettingsAlarmRingtoneSettings
import dev.studyflow.core.datastore.UploadNetworkSettings
import dev.studyflow.core.datastore.UserSettingsStore
import dev.studyflow.core.datastore.UserSettingsUploadNetworkSettings
import dev.studyflow.feature.settings.AndroidBatteryDiagnosticsSource
import dev.studyflow.feature.settings.BatteryDiagnosticsSource
import dev.studyflow.feature.settings.LogExportEnvironment
import dev.studyflow.feature.settings.LogExportFileWriter
import dev.studyflow.feature.settings.LogExporter
import dev.studyflow.feature.settings.data.MediaStoreLogExportWriter
import javax.inject.Singleton

/**
 * Binds [AlarmRingtoneSettings] to the real, persisted [UserSettingsStore] (issue #48).
 *
 * [NotificationSettingsViewModel][dev.studyflow.feature.settings.NotificationSettingsViewModel]
 * depends on the narrow [AlarmRingtoneSettings] interface rather than [UserSettingsStore] itself,
 * so a plain unit test can fake it without touching DataStore or the generated proto type.
 */
@Module
@InstallIn(SingletonComponent::class)
public object SettingsModule {
    @Provides
    @Singleton
    public fun alarmRingtoneSettings(store: UserSettingsStore): AlarmRingtoneSettings =
        UserSettingsAlarmRingtoneSettings(store)

    @Provides
    @Singleton
    public fun uploadNetworkSettings(store: UserSettingsStore): UploadNetworkSettings =
        UserSettingsUploadNetworkSettings(store)

    @Provides
    @Singleton
    public fun logExportFileWriter(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
    ): LogExportFileWriter = MediaStoreLogExportWriter(context, dispatchers)

    @Provides
    @Singleton
    public fun logExporter(
        buffer: LogBuffer,
        environment: LogExportEnvironment,
        writer: LogExportFileWriter,
        logger: AppLogger,
    ): LogExporter =
        LogExporter(
            buffer = buffer,
            environment = environment,
            writer = writer,
            logger = logger,
            clock = SystemWallClock,
        )

    @Provides
    @Singleton
    public fun batteryDiagnosticsSource(
        @ApplicationContext context: Context,
    ): BatteryDiagnosticsSource = AndroidBatteryDiagnosticsSource(context)
}
