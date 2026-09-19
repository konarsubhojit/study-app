package dev.studyflow.app.widget

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.studyflow.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One tap in the shade to start a study session, one more to finish it (issue #61).
 *
 * The tile is *listened to*, never polled: the platform calls [onStartListening] whenever the panel
 * shows it, and [requestListening] asks for that call again after a session command changes
 * something, so the label is right within a second of a change made in the app, in the notification
 * or on a widget. Nothing is scheduled, and the service is bound only while the shade is open.
 */
internal class StudyTimerTileService : TileService() {
    private lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob())
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { render(timerController().snapshot()) }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val controller = timerController()
            controller.toggleSession()
            render(controller.snapshot())
        }
    }

    private fun timerController(): WidgetTimerController = applicationContext.widgetEntryPoint().timerController()

    private fun render(snapshot: TimerWidgetSnapshot) {
        val tile = qsTile ?: return
        tile.state = if (snapshot.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_timer_label)
        tile.contentDescription =
            getString(if (snapshot.isActive) R.string.tile_timer_stop else R.string.tile_timer_start)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The subtitle is the only place elapsed time fits. It is a snapshot, refreshed when the
            // shade opens or a command is committed, never a display this service keeps ticking.
            tile.subtitle =
                if (snapshot.isActive) {
                    formatElapsed(snapshot.elapsed)
                } else {
                    getString(R.string.widget_timer_start)
                }
        }
        tile.updateTile()
    }

    internal companion object {
        /**
         * Asks the platform to call [onStartListening] again, so the tile re-reads the session log.
         *
         * A no-op unless the tile is actually added and visible, which is what keeps this cheap
         * enough to call from every committed timer command.
         */
        fun requestListening(context: Context) {
            requestListeningState(context, ComponentName(context, StudyTimerTileService::class.java))
        }
    }
}
