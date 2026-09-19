package dev.studyflow.app.widget

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.domain.tasks.TaskRepository
import dev.studyflow.core.scheduling.ReminderActionExecutor

/**
 * What the widgets and the Quick Settings tile read from the Hilt graph.
 *
 * A `GlanceAppWidget` is constructed by its receiver and a `TileService` is constructed by the
 * system, so neither can take constructor injection; an entry point is the pattern Hilt documents
 * for that, and the same one `:core:scheduling`'s receivers already use.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetEntryPoint {
    fun timerController(): WidgetTimerController

    fun taskRepository(): TaskRepository

    /** Complete and snooze are the reminder actions, reused verbatim so they behave identically. */
    fun reminderActionExecutor(): ReminderActionExecutor

    fun widgetUpdater(): WidgetUpdater

    fun dispatcherProvider(): DispatcherProvider
}

internal fun Context.widgetEntryPoint(): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)
