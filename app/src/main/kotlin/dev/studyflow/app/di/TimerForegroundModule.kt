package dev.studyflow.app.di

import android.app.AlarmManager
import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.studyflow.app.timer.LoggingTimerAccuracyReporter
import dev.studyflow.app.timer.TimerForegroundServiceController
import dev.studyflow.core.datastore.ActiveTimerStore
import dev.studyflow.core.datastore.FocusTimerSettings
import dev.studyflow.core.datastore.UserSettingsFocusTimerSettings
import dev.studyflow.core.datastore.UserSettingsStore
import dev.studyflow.core.datastore.activeTimerStore
import dev.studyflow.core.domain.session.SessionCommandObserver
import dev.studyflow.core.domain.timer.TimerAccuracyReporter
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TimerForegroundModule {
    @Binds
    @IntoSet
    abstract fun timerForegroundObserver(controller: TimerForegroundServiceController): SessionCommandObserver

    @Binds
    abstract fun timerAccuracyReporter(reporter: LoggingTimerAccuracyReporter): TimerAccuracyReporter

    companion object {
        @Provides
        @Singleton
        fun activeTimerStore(
            @ApplicationContext context: Context,
        ): ActiveTimerStore = context.activeTimerStore()

        @Provides
        @Singleton
        fun alarmManager(
            @ApplicationContext context: Context,
        ): AlarmManager = context.getSystemService(AlarmManager::class.java)

        @Provides
        @Singleton
        fun focusTimerSettings(store: UserSettingsStore): FocusTimerSettings = UserSettingsFocusTimerSettings(store)
    }
}
