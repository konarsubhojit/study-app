package dev.studyflow.core.scheduling

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.studyflow.core.domain.tasks.TaskRepository
import dev.studyflow.core.model.StudyTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The full-screen "exam at 09:00" alarm face (issue #48).
 *
 * Launched by [ReminderDeliveryCoordinator] as the `fullScreenIntent` of an
 * [dev.studyflow.core.notifications.StudyFlowNotificationChannel.ALARMS] notification. Android
 * itself decides whether that intent is *shown* full-screen (device locked / screen off) or merely
 * makes this activity available from a heads-up notification tap — this class only needs to work
 * correctly either way, which is why it sets the locked-screen flags unconditionally rather than
 * branching on device state.
 *
 * Snooze and Dismiss route through [ReminderActionReceiver] — the same background hand-off the
 * notification's own actions use — so there is exactly one place ([ReminderActionExecutor]) that
 * decides what either button does, including the snooze cap.
 */
@AndroidEntryPoint
public class AlarmActivity : ComponentActivity() {
    @Inject internal lateinit var taskRepository: TaskRepository

    private val activityScope = CoroutineScope(SupervisorJob())
    private var observeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID)
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        if (reminderId == null || taskId == null) {
            finish()
            return
        }

        val titleView = TextView(this).apply { textSize = TITLE_TEXT_SIZE_SP; setTypeface(typeface, Typeface.BOLD) }
        val dueView = TextView(this).apply { textSize = BODY_TEXT_SIZE_SP }
        setContentView(alarmLayout(titleView, dueView, reminderId, taskId))

        observeJob =
            activityScope.launch {
                taskRepository.observeTask(taskId).collect { task ->
                    if (task == null || !task.stillHasLiveReminder(reminderId)) {
                        // The alarm was dismissed, completed or timed out from elsewhere (the
                        // notification, another device, or AlarmPlaybackService's own timeout);
                        // there is nothing left for this screen to show.
                        finish()
                        return@collect
                    }
                    titleView.text = task.title
                    dueView.text = task.dueAt?.let { "Due %02d:%02d".format(it.hour, it.minute) } ?: "Due now"
                }
            }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        super.onDestroy()
    }

    /**
     * Makes this activity visible and interactive over a locked keyguard without dismissing it.
     *
     * `requestDismissKeyguard` is deliberately never called: Snooze and Dismiss must work without
     * unlocking the device, and asking the system to dismiss the keyguard would prompt for a PIN
     * on a secure lock screen, which is exactly the friction an alarm must not add.
     */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            // FLAG_DISMISS_KEYGUARD is deliberately not set here either — see the class doc: it
            // would prompt for a PIN on a secure lock screen, exactly the friction Snooze/Dismiss
            // must not add, even on these older API levels.
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun alarmLayout(
        titleView: TextView,
        dueView: TextView,
        reminderId: String,
        taskId: String,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            val paddingPx = dpToPx(PADDING_DP)
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)

            titleView.setTextColor(Color.WHITE)
            dueView.setTextColor(Color.LTGRAY)
            addView(titleView)
            addView(dueView)

            addView(
                Button(this@AlarmActivity).apply {
                    text = "Snooze"
                    setOnClickListener {
                        dispatch(ReminderActionKind.SNOOZE, reminderId, taskId)
                        finish()
                    }
                },
            )
            addView(
                Button(this@AlarmActivity).apply {
                    text = "Dismiss"
                    setOnClickListener {
                        dispatch(ReminderActionKind.DISMISS, reminderId, taskId)
                        finish()
                    }
                },
            )
        }

    private fun dispatch(
        action: ReminderActionKind,
        reminderId: String,
        taskId: String,
    ) {
        sendBroadcast(
            Intent(this, ReminderActionReceiver::class.java).apply {
                this.action = action.intentAction
                putExtra(EXTRA_REMINDER_ID, reminderId)
                putExtra(EXTRA_TASK_ID, taskId)
            },
        )
    }

    public companion object {
        public fun intent(
            context: Context,
            reminderId: String,
            taskId: String,
        ): Intent =
            Intent(context, AlarmActivity::class.java).apply {
                putExtra(EXTRA_REMINDER_ID, reminderId)
                putExtra(EXTRA_TASK_ID, taskId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }

        private const val TITLE_TEXT_SIZE_SP = 28f
        private const val BODY_TEXT_SIZE_SP = 18f
        private const val PADDING_DP = 32
    }

    /** [PADDING_DP] converted using this device's density, so padding is consistent across screens. */
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}

/**
 * True when [this] task is still open and still carries a reminder with [reminderId] — i.e. the
 * alarm this screen is showing for has not been completed, deleted or otherwise cleared elsewhere.
 */
private fun StudyTask.stillHasLiveReminder(reminderId: String): Boolean =
    !isCompleted && !deleted && reminders.any { it.id == reminderId }
