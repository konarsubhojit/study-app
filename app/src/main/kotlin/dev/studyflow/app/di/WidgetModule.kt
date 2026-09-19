package dev.studyflow.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.studyflow.app.widget.WidgetTimerController
import dev.studyflow.app.widget.WidgetUpdater
import dev.studyflow.core.common.coroutines.ApplicationScope
import dev.studyflow.core.common.time.AnchoredClock
import dev.studyflow.core.domain.session.SessionCommandObserver
import dev.studyflow.core.domain.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/** Wiring for the home-screen widgets and the Quick Settings tile (issue #61). */
@Module
@InstallIn(SingletonComponent::class)
internal object WidgetModule {
    @Provides
    @Singleton
    fun widgetTimerController(
        sessionRepository: SessionRepository,
        clock: AnchoredClock,
    ): WidgetTimerController = WidgetTimerController(sessionRepository, clock)

    @Provides
    @Singleton
    fun widgetUpdater(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): WidgetUpdater = WidgetUpdater(context, scope)

    /**
     * Refreshing widgets is an observer rather than a call inside each control, so a timer change
     * made anywhere — screen, notification, tile, widget, reboot recovery — reaches the home screen
     * by the same path.
     */
    @Provides
    @Singleton
    @IntoSet
    fun widgetSessionObserver(updater: WidgetUpdater): SessionCommandObserver = updater
}
