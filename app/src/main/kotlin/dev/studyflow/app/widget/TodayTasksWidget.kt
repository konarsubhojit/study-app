package dev.studyflow.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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
import dev.studyflow.app.R
import dev.studyflow.app.navigation.StudyFlowDeepLinks
import dev.studyflow.app.navigation.WidgetAction
import dev.studyflow.core.designsystem.theme.Spacing
import dev.studyflow.core.designsystem.theme.StudyFlowTypography
import dev.studyflow.core.model.StudyTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * "What is due today", on the home screen (issue #61).
 *
 * The list is whatever the repositories say — overdue first, then the rest of the local day — read
 * as a flow, so a task completed in the app, from a reminder notification or from this widget
 * redraws every copy of it without anything polling. Complete and Snooze run the same
 * `ReminderActionExecutor` the notification actions do, so a snooze from the home screen is capped,
 * rescheduled and recorded exactly like a snooze from the shade.
 */
internal class TodayTasksWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode =
        SizeMode.Responsive(setOf(SMALL_TASKS_WIDGET, MEDIUM_TASKS_WIDGET, LARGE_TASKS_WIDGET))

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val repository = context.widgetEntryPoint().taskRepository()
        val due: Flow<List<StudyTask>> =
            combine(repository.observeOverdue(), repository.observeToday()) { overdue, today -> overdue + today }

        provideContent {
            val tasks by due.collectAsState(emptyList())
            StudyFlowGlanceTheme {
                TodayTasksContent(tasks)
            }
        }
    }
}

/** The receiver the launcher talks to; the widget itself holds no state of its own. */
internal class TodayTasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayTasksWidget()
}

@Composable
private fun TodayTasksContent(tasks: List<StudyTask>) {
    val context = LocalContext.current
    val spacing = Spacing()
    val size = LocalSize.current
    val showsActions = size.width >= MEDIUM_TASKS_WIDGET.width
    val showsSnooze = size.width >= LARGE_TASKS_WIDGET.width

    Column(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .padding(spacing.medium),
    ) {
        Text(
            text = context.getString(R.string.widget_tasks_title, tasks.size),
            style =
                TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = StudyFlowTypography.titleSmall.fontSize,
                ),
            maxLines = 1,
            modifier = GlanceModifier.clickable(actionStartActivity(openTasksIntent(context))),
        )
        Spacer(modifier = GlanceModifier.height(spacing.small))
        if (tasks.isEmpty()) {
            Text(
                text = context.getString(R.string.widget_tasks_empty),
                style =
                    TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = StudyFlowTypography.bodySmall.fontSize,
                    ),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(tasks, itemId = { it.id.hashCode().toLong() }) { task ->
                    TaskRow(task = task, showsActions = showsActions, showsSnooze = showsSnooze)
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: StudyTask,
    showsActions: Boolean,
    showsSnooze: Boolean,
) {
    val context = LocalContext.current
    val spacing = Spacing()
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = spacing.extraSmall),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = task.title,
                style =
                    TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = StudyFlowTypography.bodyMedium.fontSize,
                    ),
                maxLines = 1,
                modifier = GlanceModifier.clickable(actionStartActivity(openTasksIntent(context))),
            )
            task.dueLabel()?.let { dueLabel ->
                Text(
                    text = dueLabel,
                    style =
                        TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = StudyFlowTypography.labelSmall.fontSize,
                        ),
                    maxLines = 1,
                )
            }
        }
        if (showsActions) {
            TaskActionButton(
                label = context.getString(R.string.widget_tasks_complete),
                action = actionRunCallback<TaskCompleteAction>(task.actionParameters()),
            )
            if (showsSnooze && task.reminders.isNotEmpty()) {
                Spacer(modifier = GlanceModifier.width(spacing.small))
                TaskActionButton(
                    label = context.getString(R.string.widget_tasks_snooze),
                    action = actionRunCallback<TaskSnoozeAction>(task.actionParameters()),
                )
            }
        }
    }
}

@Composable
private fun TaskActionButton(
    label: String,
    action: Action,
) {
    val spacing = Spacing()
    Text(
        text = label,
        style =
            TextStyle(
                color = GlanceTheme.colors.onSecondaryContainer,
                fontSize = StudyFlowTypography.labelMedium.fontSize,
            ),
        maxLines = 1,
        modifier =
            GlanceModifier
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(spacing.small)
                .padding(horizontal = spacing.small, vertical = spacing.extraSmall)
                .clickable(action),
    )
}

/** `14:30` for a timed task; nothing for an all-day one, which has no meaningful time to show. */
private fun StudyTask.dueLabel(): String? =
    dueAt?.takeUnless { isAllDay }?.let { due -> "%02d:%02d".format(due.hour, due.minute) }

private fun openTasksIntent(context: Context): Intent =
    Intent(Intent.ACTION_VIEW, StudyFlowDeepLinks.uriFor(WidgetAction.TASKS))
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
