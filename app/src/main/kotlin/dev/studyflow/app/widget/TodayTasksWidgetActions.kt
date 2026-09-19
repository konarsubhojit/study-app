package dev.studyflow.app.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.scheduling.ReminderActionKind

/**
 * Host size buckets for the tasks widget: title and count alone, then the Complete action, then
 * Snooze as well. Wider layouts add controls rather than scaling the ones a narrow cell shows.
 */
internal val SMALL_TASKS_WIDGET: DpSize = DpSize(110.dp, 70.dp)
internal val MEDIUM_TASKS_WIDGET: DpSize = DpSize(180.dp, 110.dp)
internal val LARGE_TASKS_WIDGET: DpSize = DpSize(250.dp, 150.dp)

private val TASK_ID = ActionParameters.Key<String>("taskId")
private val REMINDER_ID = ActionParameters.Key<String>("reminderId")

/**
 * Identifies the task a widget row acts on.
 *
 * The reminder id travels with it because [ReminderActionExecutor][dev.studyflow.core.scheduling.ReminderActionExecutor]
 * is written for a notification and keys its work off the reminder; a task with no reminder still
 * completes, it simply has nothing to snooze.
 */
internal fun StudyTask.actionParameters(): ActionParameters =
    actionParametersOf(
        TASK_ID to id,
        REMINDER_ID to (reminders.firstOrNull()?.id ?: id),
    )

/** Ticks a task off from the home screen, cancelling its reminders like the notification does. */
internal class TaskCompleteAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        context.runTaskAction(ReminderActionKind.COMPLETE, parameters)
    }
}

/** Postpones a task's next reminder, capped exactly as a snooze from the notification shade is. */
internal class TaskSnoozeAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        context.runTaskAction(ReminderActionKind.SNOOZE, parameters)
    }
}

private suspend fun Context.runTaskAction(
    kind: ReminderActionKind,
    parameters: ActionParameters,
) {
    val taskId = parameters[TASK_ID] ?: return
    val reminderId = parameters[REMINDER_ID] ?: taskId
    val entryPoint = widgetEntryPoint()
    entryPoint.reminderActionExecutor().execute(kind, reminderId, taskId)
    entryPoint.widgetUpdater().refresh()
}
