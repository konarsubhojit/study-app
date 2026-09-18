package dev.studyflow.app.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.studyflow.app.R
import dev.studyflow.app.navigation.StudyFlowDeepLinks
import dev.studyflow.app.navigation.TimerRoute
import dev.studyflow.core.common.coroutines.ApplicationScope
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.time.AnchoredClock
import dev.studyflow.core.datastore.FocusTimerConfig
import dev.studyflow.core.datastore.FocusTimerSettings
import dev.studyflow.core.domain.session.SessionCommandResult
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.TimeAnchor
import dev.studyflow.core.notifications.NotificationAction
import dev.studyflow.core.notifications.StudyFlowNotificationChannel
import dev.studyflow.core.notifications.StudyFlowNotificationFactory
import dev.studyflow.core.notifications.StudyFlowNotifier
import dev.studyflow.core.notifications.StudyFlowPendingIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@Singleton
internal class TimerIntervalCoordinator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val alarmManager: AlarmManager,
        private val sessionRepository: Lazy<SessionRepository>,
        private val settings: FocusTimerSettings,
        private val clock: AnchoredClock,
        private val notificationFactory: StudyFlowNotificationFactory,
        private val notifier: StudyFlowNotifier,
        private val logger: AppLogger,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) {
        fun refreshSchedules(state: TimerState) {
            applicationScope.launch {
                when (state) {
                    is TimerState.Running -> scheduleRunning(state, settings.config.first())
                    TimerState.Idle, is TimerState.Paused, is TimerState.Stopped -> cancelScheduledAlarms()
                }
            }
        }

        fun handle(intent: Intent, onComplete: () -> Unit) {
            applicationScope.launch {
                try {
                    when (intent.action) {
                        ACTION_FOCUS_ENDED -> onFocusEnded(intent)
                        ACTION_BREAK_ENDED -> onBreakEnded(intent)
                        ACTION_RESUME_FOCUS -> onResumeFocus(intent)
                        ACTION_INACTIVITY_PROMPT -> onInactivityPrompt(intent)
                        ACTION_CONFIRM_ACTIVITY -> onConfirmActivity(intent)
                        ACTION_STOP_AT_PROMPT -> onStopAtPrompt(intent)
                        ACTION_RUNAWAY_SESSION -> onRunawaySession(intent)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (
                    @Suppress("TooGenericExceptionCaught") failure: Throwable,
                ) {
                    logger.warning(TAG, "Timer interval alarm handling failed", failure)
                } finally {
                    onComplete()
                }
            }
        }

        private suspend fun scheduleRunning(
            state: TimerState.Running,
            config: FocusTimerConfig,
        ) {
            cancelBreakAlarm()
            scheduleAlarm(
                action = ACTION_FOCUS_ENDED,
                requestCode = REQUEST_FOCUS_ENDED,
                sessionId = state.sessionId,
                expectedAnchor = state.openedAt,
                triggerAt = state.openedAt + config.focusInterval,
            )
            scheduleAlarm(
                action = ACTION_INACTIVITY_PROMPT,
                requestCode = REQUEST_INACTIVITY_PROMPT,
                sessionId = state.sessionId,
                expectedAnchor = state.lastConfirmedAt,
                triggerAt = state.lastConfirmedAt + config.inactivityPromptAfter,
            )
            scheduleAlarm(
                action = ACTION_RUNAWAY_SESSION,
                requestCode = REQUEST_RUNAWAY_SESSION,
                sessionId = state.sessionId,
                expectedAnchor = state.openedAt,
                triggerAt = state.openedAt + config.maximumSessionDuration,
            )
        }

        private suspend fun onFocusEnded(intent: Intent) {
            val state = sessionRepository.get().activeState()
            val triggerAt = intent.requiredAnchor(EXTRA_TRIGGER)
            if (state !is TimerState.Running || !state.matchesExpectedOpen(intent)) return

            val result =
                sessionRepository.get().execute(
                    command = TimerCommand.StartBreak,
                    eventId = UUID.randomUUID().toString(),
                    anchor = triggerAt,
                )
            val pausedState = (result as? SessionCommandResult.Applied)?.state as? TimerState.Paused ?: return

            postFocusComplete(pausedState)
            scheduleBreakEnd(pausedState, triggerAt, settings.config.first().breakInterval)
        }

        private suspend fun scheduleBreakEnd(
            state: TimerState.Paused,
            breakStartedAt: TimeAnchor,
            breakInterval: Duration,
        ) {
            scheduleAlarm(
                action = ACTION_BREAK_ENDED,
                requestCode = REQUEST_BREAK_ENDED,
                sessionId = state.sessionId,
                expectedSequence = state.lastSequence,
                expectedAnchor = breakStartedAt,
                triggerAt = breakStartedAt + breakInterval,
            )
        }

        private suspend fun onBreakEnded(intent: Intent) {
            val state = sessionRepository.get().activeState()
            if (state !is TimerState.Paused || !state.matchesExpectedSequence(intent)) return
            postBreakComplete(state)
        }

        private suspend fun onResumeFocus(intent: Intent) {
            val state = sessionRepository.get().activeState()
            if (state !is TimerState.Paused || state.sessionId != intent.getStringExtra(EXTRA_SESSION_ID)) return
            sessionRepository.get().execute(TimerCommand.ResumeFocus, UUID.randomUUID().toString(), clock.anchor())
        }

        private suspend fun onInactivityPrompt(intent: Intent) {
            val state = sessionRepository.get().activeState()
            if (state !is TimerState.Running || !state.matchesExpectedConfirmation(intent)) return
            postInactivityPrompt(state, intent.requiredAnchor(EXTRA_TRIGGER))
        }

        private suspend fun onConfirmActivity(intent: Intent) {
            val state = sessionRepository.get().activeState()
            if (state !is TimerState.Running || state.sessionId != intent.getStringExtra(EXTRA_SESSION_ID)) return
            sessionRepository.get().execute(TimerCommand.ConfirmActivity, UUID.randomUUID().toString(), clock.anchor())
        }

        private suspend fun onStopAtPrompt(intent: Intent) {
            val state = sessionRepository.get().activeState()
            val promptAt = intent.requiredAnchor(EXTRA_PROMPT)
            if (state !is TimerState.Running || state.sessionId != intent.getStringExtra(EXTRA_SESSION_ID)) return
            sessionRepository.get().execute(TimerCommand.Stop, UUID.randomUUID().toString(), promptAt)
        }

        private suspend fun onRunawaySession(intent: Intent) {
            val state = sessionRepository.get().activeState()
            if (state !is TimerState.Running || !state.matchesExpectedOpen(intent)) return
            val result =
                sessionRepository
                    .get()
                    .execute(TimerCommand.Stop, UUID.randomUUID().toString(), intent.requiredAnchor(EXTRA_TRIGGER))
            if (result is SessionCommandResult.Applied) postRunawayStopped()
        }

        private fun postFocusComplete(state: TimerState.Paused) {
            notifier.post(
                id = NOTIFICATION_FOCUS_COMPLETE,
                channel = StudyFlowNotificationChannel.FOCUS_INTERVALS,
                notification =
                    notificationFactory.alert(
                        channel = StudyFlowNotificationChannel.FOCUS_INTERVALS,
                        title = context.getString(R.string.timer_focus_complete_title),
                        text = context.getString(R.string.timer_focus_complete_text),
                        contentIntent = openIntent(),
                        actions =
                            listOf(
                                NotificationAction(
                                    title = context.getString(R.string.timer_notification_action_resume),
                                    icon = R.drawable.ic_notification,
                                    intent = actionIntent(ACTION_RESUME_FOCUS, REQUEST_RESUME_FOCUS, state.sessionId),
                                ),
                            ),
                    ),
            )
        }

        private fun postBreakComplete(state: TimerState.Paused) {
            notifier.post(
                id = NOTIFICATION_BREAK_COMPLETE,
                channel = StudyFlowNotificationChannel.FOCUS_INTERVALS,
                notification =
                    notificationFactory.alert(
                        channel = StudyFlowNotificationChannel.FOCUS_INTERVALS,
                        title = context.getString(R.string.timer_break_complete_title),
                        text = context.getString(R.string.timer_break_complete_text),
                        contentIntent = openIntent(),
                        actions =
                            listOf(
                                NotificationAction(
                                    title = context.getString(R.string.timer_notification_action_resume),
                                    icon = R.drawable.ic_notification,
                                    intent = actionIntent(ACTION_RESUME_FOCUS, REQUEST_RESUME_FOCUS, state.sessionId),
                                ),
                            ),
                    ),
            )
        }

        private fun postInactivityPrompt(
            state: TimerState.Running,
            promptAt: TimeAnchor,
        ) {
            notifier.post(
                id = NOTIFICATION_INACTIVITY,
                channel = StudyFlowNotificationChannel.FOCUS_INTERVALS,
                notification =
                    notificationFactory.alert(
                        channel = StudyFlowNotificationChannel.FOCUS_INTERVALS,
                        title = context.getString(R.string.timer_still_studying_title),
                        text = context.getString(R.string.timer_still_studying_text),
                        contentIntent = openIntent(),
                        actions =
                            listOf(
                                NotificationAction(
                                    title = context.getString(R.string.timer_action_still_studying),
                                    icon = R.drawable.ic_notification,
                                    intent =
                                        actionIntent(
                                            ACTION_CONFIRM_ACTIVITY,
                                            REQUEST_CONFIRM_ACTIVITY,
                                            state.sessionId,
                                        ),
                                ),
                                NotificationAction(
                                    title = context.getString(R.string.timer_action_stop_at_prompt),
                                    icon = R.drawable.ic_notification,
                                    intent = stopAtPromptIntent(state.sessionId, promptAt),
                                ),
                            ),
                    ),
            )
        }

        private fun postRunawayStopped() {
            notifier.post(
                id = NOTIFICATION_RUNAWAY,
                channel = StudyFlowNotificationChannel.FOCUS_INTERVALS,
                notification =
                    notificationFactory.alert(
                        channel = StudyFlowNotificationChannel.FOCUS_INTERVALS,
                        title = context.getString(R.string.timer_runaway_paused_title),
                        text = context.getString(R.string.timer_runaway_paused_text),
                        contentIntent = openIntent(),
                        actions =
                            listOf(
                                NotificationAction(
                                    title = context.getString(R.string.timer_notification_action_open),
                                    icon = R.drawable.ic_notification,
                                    intent = openIntent(),
                                ),
                            ),
                    ),
            )
        }

        private fun scheduleAlarm(
            action: String,
            requestCode: Int,
            sessionId: String,
            expectedAnchor: TimeAnchor,
            triggerAt: TimeAnchor,
            expectedSequence: Long? = null,
        ) {
            val intent =
                Intent(context, TimerIntervalReceiver::class.java)
                    .setAction(action)
                    .setPackage(context.packageName)
                    .setData(timerActionUri(action))
                    .putExtra(EXTRA_SESSION_ID, sessionId)
                    .putExtra(EXTRA_EXPECTED_SEQUENCE, expectedSequence ?: -1L)
                    .withAnchor(EXTRA_EXPECTED, expectedAnchor)
                    .withAnchor(EXTRA_TRIGGER, triggerAt)
            val operation = StudyFlowPendingIntents.broadcast(context, requestCode, intent)
            val triggerUptimeMillis =
                maxOf(clock.anchor().uptime.inWholeMilliseconds, triggerAt.uptime.inWholeMilliseconds)
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerUptimeMillis,
                    operation,
                )
            } catch (denied: SecurityException) {
                logger.warning(TAG, "Exact timer interval alarm denied; falling back to alarm clock", denied)
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAt.wallClock.toEpochMilliseconds(), openIntent()),
                    operation,
                )
            }
        }

        private fun cancelScheduledAlarms() {
            cancelAlarm(ACTION_FOCUS_ENDED, REQUEST_FOCUS_ENDED)
            cancelAlarm(ACTION_BREAK_ENDED, REQUEST_BREAK_ENDED)
            cancelAlarm(ACTION_INACTIVITY_PROMPT, REQUEST_INACTIVITY_PROMPT)
            cancelAlarm(ACTION_RUNAWAY_SESSION, REQUEST_RUNAWAY_SESSION)
        }

        private fun cancelBreakAlarm() {
            cancelAlarm(ACTION_BREAK_ENDED, REQUEST_BREAK_ENDED)
        }

        private fun cancelAlarm(
            action: String,
            requestCode: Int,
        ) {
            alarmManager.cancel(
                StudyFlowPendingIntents.broadcast(
                    context = context,
                    requestCode = requestCode,
                    intent =
                        Intent(context, TimerIntervalReceiver::class.java)
                            .setAction(action)
                            .setPackage(context.packageName)
                            .setData(timerActionUri(action)),
                ),
            )
        }

        private fun actionIntent(
            action: String,
            requestCode: Int,
            sessionId: String,
        ): PendingIntent =
            StudyFlowPendingIntents.broadcast(
                context = context,
                requestCode = requestCode,
                intent =
                    Intent(context, TimerIntervalReceiver::class.java)
                        .setAction(action)
                        .setPackage(context.packageName)
                        .setData(timerActionUri(action))
                        .putExtra(EXTRA_SESSION_ID, sessionId),
            )

        private fun stopAtPromptIntent(
            sessionId: String,
            promptAt: TimeAnchor,
        ): PendingIntent =
            StudyFlowPendingIntents.broadcast(
                context = context,
                requestCode = REQUEST_STOP_AT_PROMPT,
                intent =
                    Intent(context, TimerIntervalReceiver::class.java)
                        .setAction(ACTION_STOP_AT_PROMPT)
                        .setPackage(context.packageName)
                        .setData(timerActionUri(ACTION_STOP_AT_PROMPT))
                        .putExtra(EXTRA_SESSION_ID, sessionId)
                        .withAnchor(EXTRA_PROMPT, promptAt),
            )

        private fun openIntent(): PendingIntent =
            StudyFlowPendingIntents.activity(
                context = context,
                requestCode = REQUEST_OPEN,
                deepLink = StudyFlowDeepLinks.uriFor(TimerRoute(openRunningTimer = true)),
            )

        private fun TimerState.Running.matchesExpectedOpen(intent: Intent): Boolean =
            sessionId == intent.getStringExtra(EXTRA_SESSION_ID) &&
                openedAt.matches(intent.requiredAnchor(EXTRA_EXPECTED))

        private fun TimerState.Running.matchesExpectedConfirmation(intent: Intent): Boolean =
            sessionId == intent.getStringExtra(EXTRA_SESSION_ID) &&
                lastConfirmedAt.matches(intent.requiredAnchor(EXTRA_EXPECTED))

        private fun TimerState.Paused.matchesExpectedSequence(intent: Intent): Boolean =
            sessionId == intent.getStringExtra(EXTRA_SESSION_ID) &&
                lastSequence == intent.getLongExtra(EXTRA_EXPECTED_SEQUENCE, -1L)

        private fun Intent.withAnchor(
            prefix: String,
            anchor: TimeAnchor,
        ): Intent =
            putExtra("${prefix}_uptime_ms", anchor.uptime.inWholeMilliseconds)
                .putExtra("${prefix}_wall_ms", anchor.wallClock.toEpochMilliseconds())
                .putExtra("${prefix}_boot_id", anchor.bootId.value)

        private fun Intent.requiredAnchor(prefix: String): TimeAnchor =
            TimeAnchor(
                uptime = getLongExtra("${prefix}_uptime_ms", 0L).milliseconds,
                wallClock = Instant.fromEpochMilliseconds(getLongExtra("${prefix}_wall_ms", 0L)),
                bootId = BootId(requireNotNull(getStringExtra("${prefix}_boot_id")) { "missing $prefix boot id" }),
            )

        private fun TimeAnchor.matches(other: TimeAnchor): Boolean =
            bootId == other.bootId && uptime == other.uptime && wallClock == other.wallClock

        private fun timerActionUri(action: String): Uri = "studyflow://timer/interval/$action".toUri()

        private operator fun TimeAnchor.plus(duration: Duration): TimeAnchor =
            TimeAnchor(uptime = uptime + duration, wallClock = wallClock + duration, bootId = bootId)

        private companion object {
            const val ACTION_FOCUS_ENDED = "dev.studyflow.app.timer.action.FOCUS_ENDED"
            const val ACTION_BREAK_ENDED = "dev.studyflow.app.timer.action.BREAK_ENDED"
            const val ACTION_RESUME_FOCUS = "dev.studyflow.app.timer.action.RESUME_FOCUS"
            const val ACTION_INACTIVITY_PROMPT = "dev.studyflow.app.timer.action.INACTIVITY_PROMPT"
            const val ACTION_CONFIRM_ACTIVITY = "dev.studyflow.app.timer.action.CONFIRM_ACTIVITY"
            const val ACTION_STOP_AT_PROMPT = "dev.studyflow.app.timer.action.STOP_AT_PROMPT"
            const val ACTION_RUNAWAY_SESSION = "dev.studyflow.app.timer.action.RUNAWAY_SESSION"

            const val EXTRA_SESSION_ID = "session_id"
            const val EXTRA_EXPECTED = "expected"
            const val EXTRA_TRIGGER = "trigger"
            const val EXTRA_PROMPT = "prompt"
            const val EXTRA_EXPECTED_SEQUENCE = "expected_sequence"

            const val REQUEST_OPEN = 33_200
            const val REQUEST_FOCUS_ENDED = 33_201
            const val REQUEST_BREAK_ENDED = 33_202
            const val REQUEST_RESUME_FOCUS = 33_203
            const val REQUEST_INACTIVITY_PROMPT = 33_204
            const val REQUEST_CONFIRM_ACTIVITY = 33_205
            const val REQUEST_STOP_AT_PROMPT = 33_206
            const val REQUEST_RUNAWAY_SESSION = 33_207

            const val NOTIFICATION_FOCUS_COMPLETE = 33_301
            const val NOTIFICATION_BREAK_COMPLETE = 33_302
            const val NOTIFICATION_INACTIVITY = 33_303
            const val NOTIFICATION_RUNAWAY = 33_304

            const val TAG = "TimerIntervals"
        }
    }

@AndroidEntryPoint
internal class TimerIntervalReceiver : BroadcastReceiver() {
    @Inject
    internal lateinit var coordinator: TimerIntervalCoordinator

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pendingResult = goAsync()
        coordinator.handle(intent, pendingResult::finish)
    }
}
