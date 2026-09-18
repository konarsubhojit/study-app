package dev.studyflow.feature.settings.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.datastore.AlarmRingtoneSettings
import dev.studyflow.core.datastore.UserSettingsAlarmRingtoneSettings
import dev.studyflow.core.datastore.UserSettingsStore
import dev.studyflow.feature.settings.AndroidBatteryDiagnosticsSource
import dev.studyflow.feature.settings.BatteryDiagnosticsSource
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
    public fun batteryDiagnosticsSource(
        @ApplicationContext context: Context,
    ): BatteryDiagnosticsSource = AndroidBatteryDiagnosticsSource(context)
}
