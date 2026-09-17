package dev.studyflow.feature.settings.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.datastore.AlarmRingtoneSettings
import dev.studyflow.core.datastore.UserSettingsAlarmRingtoneSettings
import dev.studyflow.core.datastore.UserSettingsStore
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
}
