package dev.studyflow.app.timer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.studyflow.app.R
import dev.studyflow.app.navigation.StudyFlowDeepLinks
import dev.studyflow.app.navigation.TimerRoute
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.datastore.ActiveTimer
import dev.studyflow.core.datastore.ActiveTimerStore
import dev.studyflow.core.domain.session.SessionCommandObserver
import dev.studyflow.core.domain.session.SessionCommandResult
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.domain.timer.TimerEngine
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.TimeAnchor
import dev.studyflow.core.notifications.ChronometerPresentation
import dev.studyflow.core.notifications.NotificationAction
import dev.studyflow.core.notifications.NotificationChannelRegistrar
import dev.studyflow.core.notifications.StudyFlowNotificationFactory
import dev.studyflow.core.notifications.StudyFlowNotifier
import dev.studyflow.core.notifications.StudyFlowPendingIntents
import dev.studyflow.core.scheduling.AndroidElapsedRealtimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val NOTIFICATION_ID = 33_003
private const val REQUEST_OPEN = 33_100
private const val REQUEST_PAUSE = 33_101
private const val REQUEST_RESUME = 33_102
private const val REQUEST_STOP = 33_103

@AndroidEntryPoint
internal class TimerForegroundService : Service() {
    @Inject lateinit var sessionRepository: SessionRepository
    @Inject lateinit var notificationFactory: StudyFlowNotificationFactory
    @Inject lateinit var notificationChannelRegistrar: NotificationChannelRegistrar
    @Inject lateinit var notifier: StudyFlowNotifier
    @Inject lateinit var activeTimerStore: ActiveTimerStore
    @Inject lateinit var dispatcherProvider: DispatcherProvider
    @Inject lateinit var wallClock: Clock

    private lateinit var scope: CoroutineScope
    private val foregroundStarted = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (foregroundStarted.compareAndSet(false, true)) startPlaceholderForeground()
        scope.launch {
            when (intent?.action) {
                ACTION_REFRESH -> refreshFromRepository()
                ACTION_PAUSE -> applyCommand(TimerCommand.Pause)
                ACTION_RESUME -> applyCommand(TimerCommand.Resume)
                ACTION_STOP -> applyCommand(TimerCommand.Stop)
                else -> refreshFromRepository()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun applyCommand(command: TimerCommand) {
        val result = sessionRepository.execute(
            command = command,
            eventId = UUID.randomUUID().toString(),
            anchor = currentAnchor(),
        )
        val nextState =
            when (result) {
                is SessionCommandResult.Applied -> result.state
                else -> sessionRepository.activeState()
            }
        refresh(nextState)
    }

    private suspend fun refreshFromRepository() {
        refresh(sessionRepository.activeState())
    }

    private suspend fun refresh(state: TimerState) {
        when (state) {
            TimerState.Idle, is TimerState.Stopped -> stopTimerForeground()
            is TimerState.Running -> {
                activeTimerStore.set(state.activeTimer())
                startTimerForeground(state)
            }
            is TimerState.Paused -> {
                // Only a ticking interval has an anchor to recover; paused elapsed time is already
                // settled in the event log.
                activeTimerStore.clear()
                startTimerForeground(state)
            }
        }
    }

    private fun startPlaceholderForeground() {
        notificationChannelRegistrar.register()
        val notification =
            notificationFactory.ongoingChronometer(
                title = getString(R.string.timer_notification_title),
                text = getString(R.string.timer_notification_restoring),
                startedAtEpochMillis = wallClock.now().toEpochMilliseconds(),
                contentIntent = openIntent(),
                chronometer = ChronometerPresentation(usesChronometer = false),
                )
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, timerForegroundServiceType)
    }

    private fun startTimerForeground(state: TimerState.Active) {
            notificationChannelRegistrar.register()
            val now = currentAnchor()
            val notification =
                notificationFactory.ongoingChronometer(
                    title = getString(R.string.timer_notification_title),
                text =
                    if (state is TimerState.Running) {
                        getString(R.string.timer_notification_running)
                    } else {
                        getString(R.string.timer_notification_paused)
                    },
                startedAtEpochMillis = chronometerWhenEpochMillis(state, now),
                contentIntent = openIntent(),
                actions = actionsFor(state),
                chronometer = ChronometerPresentation(usesChronometer = state is TimerState.Running),
            )
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, timerForegroundServiceType)
        foregroundStarted.set(true)
    }

    private suspend fun stopTimerForeground() {
        activeTimerStore.clear()
        notifier.cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted.set(false)
        stopSelf()
    }

    private fun chronometerWhenEpochMillis(
        state: TimerState.Active,
        now: TimeAnchor,
    ): Long =
        now.wallClock.toEpochMilliseconds() -
            TimerEngine
                .elapsedAt(state, now)
                .counted
                .inWholeMilliseconds

    private fun actionsFor(state: TimerState.Active): List<NotificationAction> =
        listOf(
            if (state is TimerState.Running) {
                NotificationAction(
                    getString(R.string.timer_notification_action_pause),
                    R.drawable.ic_notification,
                    serviceIntent(ACTION_PAUSE, REQUEST_PAUSE),
                )
            } else {
                NotificationAction(
                    getString(R.string.timer_notification_action_resume),
                    R.drawable.ic_notification,
                    serviceIntent(ACTION_RESUME, REQUEST_RESUME),
                )
            },
            NotificationAction(
                getString(R.string.timer_notification_action_stop),
                R.drawable.ic_notification,
                serviceIntent(ACTION_STOP, REQUEST_STOP),
            ),
            NotificationAction(
                getString(R.string.timer_notification_action_open),
                R.drawable.ic_notification,
                openIntent(),
            ),
        )

    private fun serviceIntent(
        action: String,
        requestCode: Int,
    ) = StudyFlowPendingIntents.service(
        context = this,
        requestCode = requestCode,
        intent = Intent(this, TimerForegroundService::class.java).setAction(action),
    )

    private fun openIntent() =
        StudyFlowPendingIntents.activity(
            context = this,
            requestCode = REQUEST_OPEN,
            deepLink = StudyFlowDeepLinks.uriFor(TimerRoute(openRunningTimer = true)),
        )

    private fun TimerState.Running.activeTimer(): ActiveTimer =
        ActiveTimer(
            sessionId = sessionId,
            wallClockEpochMillis = openedAt.wallClock.toEpochMilliseconds(),
            uptimeMillis = openedAt.uptime.inWholeMilliseconds,
            bootId = openedAt.bootId.value,
        )

    private fun currentAnchor(): TimeAnchor =
        TimeAnchor(
            uptime = AndroidElapsedRealtimeSource.uptime(),
            wallClock = wallClock.now(),
            bootId = BootId(Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0).toString()),
        )

    private val timerForegroundServiceType: Int
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }

    internal companion object {
        private const val ACTION_REFRESH = "dev.studyflow.app.timer.action.REFRESH"
        private const val ACTION_PAUSE = "dev.studyflow.app.timer.action.PAUSE"
        private const val ACTION_RESUME = "dev.studyflow.app.timer.action.RESUME"
        private const val ACTION_STOP = "dev.studyflow.app.timer.action.STOP"

        fun refreshIntent(context: Context): Intent =
            Intent(context, TimerForegroundService::class.java)
                .setAction(ACTION_REFRESH)
                .setPackage(context.packageName)
    }
}

class TimerForegroundServiceController
    @Inject
    constructor(
        @ApplicationContext
        private val context: Context,
    ) : SessionCommandObserver {
        override fun onSessionCommandApplied(result: SessionCommandResult.Applied) {
            ContextCompat.startForegroundService(context, TimerForegroundService.refreshIntent(context))
        }
    }
