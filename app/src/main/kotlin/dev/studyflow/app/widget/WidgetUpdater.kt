package dev.studyflow.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.updateAll
import dev.studyflow.core.domain.session.SessionCommandObserver
import dev.studyflow.core.domain.session.SessionCommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Pushes a freshly committed timer state out to the home screen and the Quick Settings tile.
 *
 * This is the reason no widget has an `updatePeriodMillis`: nothing polls, because every change
 * that can happen — in the app, from the notification, from the tile, from a widget button — ends
 * in a committed session command, and a committed command lands here. A widget whose host session
 * is already alive would re-render from its own flow anyway; [GlanceAppWidget.updateAll] is what
 * covers the ones that are not, without waking anything up on an idle device.
 */
internal class WidgetUpdater(
    private val context: Context,
    private val scope: CoroutineScope,
) : SessionCommandObserver {
    override fun onSessionCommandApplied(result: SessionCommandResult.Applied) {
        refresh()
    }

    /** Re-renders every StudyFlow widget and asks the platform to re-poll the tile. */
    fun refresh() {
        scope.launch {
            TimerWidget().updateAll(context)
            TodayTasksWidget().updateAll(context)
        }
        StudyTimerTileService.requestListening(context)
    }
}
