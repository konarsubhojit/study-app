package dev.studyflow.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

/**
 * Start, pause or resume from the timer widget.
 *
 * The callback runs in the app process — Glance starts it for the tap even when the process was
 * dead — and only writes to the session log; re-rendering is [WidgetUpdater]'s job, so a control
 * cannot show a state that was never committed.
 */
internal class TimerPlaybackAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        context.widgetEntryPoint().timerController().togglePlayback()
    }
}

/** Stops the running or paused session from the timer widget. */
internal class TimerStopAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        context.widgetEntryPoint().timerController().stop()
    }
}
