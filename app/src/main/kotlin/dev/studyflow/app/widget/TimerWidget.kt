package dev.studyflow.app.widget

import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.studyflow.app.R
import dev.studyflow.app.navigation.StudyFlowDeepLinks
import dev.studyflow.app.navigation.WidgetAction
import dev.studyflow.core.designsystem.theme.Spacing
import dev.studyflow.core.designsystem.theme.StudyFlowTypography
import kotlinx.coroutines.flow.Flow

/**
 * Host size buckets, not design tokens: the launcher decides how big a widget cell is, and these
 * are the widths below which a control has to go rather than shrink. `SizeMode.Responsive` renders
 * one layout per bucket up front, so a resize redraws without waking the app.
 */
private val SMALL_WIDGET = DpSize(110.dp, 70.dp)
private val MEDIUM_WIDGET = DpSize(180.dp, 70.dp)
private val LARGE_WIDGET = DpSize(250.dp, 150.dp)

/**
 * The study-timer home-screen widget (issue #61).
 *
 * Elapsed time is drawn by the platform's own `Chronometer` through [AndroidRemoteViews]: the
 * launcher counts the seconds in its process, so a running timer stays correct on the home screen
 * with no periodic update job, no alarm and no work on an idle device. Everything else — the
 * phase, the controls — changes only when a session command is committed, which is exactly when
 * [WidgetUpdater] pushes a new rendering.
 */
internal class TimerWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL_WIDGET, MEDIUM_WIDGET, LARGE_WIDGET))

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val controller = context.widgetEntryPoint().timerController()
        val initial = controller.snapshot()
        val snapshots: Flow<TimerWidgetSnapshot> = controller.snapshots()

        provideContent {
            val snapshot by snapshots.collectAsState(initial)
            StudyFlowGlanceTheme {
                TimerWidgetContent(snapshot)
            }
        }
    }
}

/** The receiver the launcher talks to; the widget itself holds no state of its own. */
internal class TimerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TimerWidget()
}

@Composable
private fun TimerWidgetContent(snapshot: TimerWidgetSnapshot) {
    val context = LocalContext.current
    val spacing = Spacing()
    val width = LocalSize.current.width
    val showsStop = snapshot.isActive && width >= MEDIUM_WIDGET.width
    val detail = snapshot.detail(context).takeIf { width >= LARGE_WIDGET.width }

    Column(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .widgetCornerRadius()
                .padding(spacing.medium)
                .clickable(actionStartActivity(openTimerIntent(context))),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.Start,
    ) {
        Text(
            text = context.getString(snapshot.statusLabel),
            style =
                TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = StudyFlowTypography.labelMedium.fontSize,
                ),
            maxLines = 1,
        )
        ElapsedTime(snapshot)
        if (detail != null) {
            Text(
                text = detail,
                style =
                    TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = StudyFlowTypography.bodySmall.fontSize,
                    ),
                maxLines = 1,
            )
        }
        Spacer(modifier = GlanceModifier.height(spacing.small))
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Button(
                text = context.getString(snapshot.playbackActionLabel),
                onClick = actionRunCallback<TimerPlaybackAction>(),
                modifier = if (showsStop) GlanceModifier.defaultWeight() else GlanceModifier,
            )
            if (showsStop) {
                Spacer(modifier = GlanceModifier.width(spacing.small))
                Button(
                    text = context.getString(R.string.widget_timer_stop),
                    onClick = actionRunCallback<TimerStopAction>(),
                )
            }
        }
    }
}

/**
 * The counting part of the widget.
 *
 * Running sessions get a platform `Chronometer` started from the monotonic clock reading the event
 * log folds to, so the display keeps counting without the app being alive; a paused or absent
 * session has nothing to count, so it is plain text.
 */
@Composable
private fun ElapsedTime(snapshot: TimerWidgetSnapshot) {
    val context = LocalContext.current
    val color = GlanceTheme.colors.onSurface
    if (snapshot.isRunning) {
        AndroidRemoteViews(remoteViews = chronometerViews(context, snapshot, color))
    } else {
        Text(
            text = formatElapsed(snapshot.elapsed),
            style =
                TextStyle(
                    color = color,
                    fontSize = StudyFlowTypography.headlineSmall.fontSize,
                ),
            maxLines = 1,
        )
    }
}

private fun chronometerViews(
    context: Context,
    snapshot: TimerWidgetSnapshot,
    color: ColorProvider,
): RemoteViews =
    RemoteViews(context.packageName, R.layout.widget_timer_chronometer).apply {
        setChronometer(R.id.widget_timer_chronometer, snapshot.chronometerBaseMillis, null, true)
        setTextColor(R.id.widget_timer_chronometer, color.getColor(context).toArgb())
        setTextViewTextSize(
            R.id.widget_timer_chronometer,
            TypedValue.COMPLEX_UNIT_SP,
            StudyFlowTypography.headlineSmall.fontSize.value,
        )
    }

private fun openTimerIntent(context: Context): Intent =
    Intent(Intent.ACTION_VIEW, StudyFlowDeepLinks.uriFor(WidgetAction.TIMER))
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

/**
 * The line under the clock: the honesty warning first, because unverified time is the one thing the
 * user has to act on, then whatever the session was noted as.
 */
private fun TimerWidgetSnapshot.detail(context: Context): String? =
    when {
        hasUnverifiedTime -> context.getString(R.string.widget_timer_unverified)
        else -> note
    }

private val TimerWidgetSnapshot.statusLabel: Int
    get() =
        when (phase) {
            TimerWidgetPhase.IDLE -> R.string.widget_timer_idle
            TimerWidgetPhase.RUNNING -> R.string.widget_timer_running
            TimerWidgetPhase.PAUSED -> R.string.widget_timer_paused
        }

private val TimerWidgetSnapshot.playbackActionLabel: Int
    get() =
        when (phase) {
            TimerWidgetPhase.IDLE -> R.string.widget_timer_start
            TimerWidgetPhase.RUNNING -> R.string.widget_timer_pause
            TimerWidgetPhase.PAUSED -> R.string.widget_timer_resume
        }
