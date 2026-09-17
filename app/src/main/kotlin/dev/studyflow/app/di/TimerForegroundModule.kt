package dev.studyflow.app.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.studyflow.app.timer.TimerForegroundServiceController
import dev.studyflow.core.datastore.ActiveTimerStore
import dev.studyflow.core.datastore.activeTimerStore
import dev.studyflow.core.domain.session.SessionCommandObserver
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TimerForegroundBindings {
    @Binds
    @IntoSet
    abstract fun timerForegroundObserver(controller: TimerForegroundServiceController): SessionCommandObserver
}

@Module
@InstallIn(SingletonComponent::class)
object TimerForegroundStores {
    @Provides
    @Singleton
    fun activeTimerStore(
        @ApplicationContext context: Context,
    ): ActiveTimerStore = context.activeTimerStore()
}
