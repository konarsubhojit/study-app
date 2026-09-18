package dev.studyflow.core.scheduling

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.datastore.UserSettingsStore
import dev.studyflow.core.domain.tasks.TaskRepository
import dev.studyflow.core.notifications.StudyFlowNotificationChannel
import dev.studyflow.core.notifications.StudyFlowNotificationFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.time.Duration

/**
 * Rings and vibrates for a fired [dev.studyflow.core.model.ReminderPrecision.ALARM] reminder
 * (issue #48).
 *
 * A foreground service rather than letting [AlarmActivity] own the `MediaPlayer` directly: the
 * alarm must keep ringing if the activity is destroyed and recreated (a locked-screen config
 * change, the user switching away and the activity being torn down under memory pressure) and must
 * stop the instant the task is completed from anywhere else in the app — a service that outlives
 * one activity instance and observes the task itself is what makes both of those reliable.
 *
 * One instance handles every simultaneously-ringing alarm, keyed by [notificationId], so two exam
 * reminders firing together do not fight over a single `MediaPlayer`.
 */
@AndroidEntryPoint
public class AlarmPlaybackService : Service() {
    @Inject internal lateinit var taskRepository: TaskRepository

    @Inject internal lateinit var settingsStore: UserSettingsStore

    @Inject internal lateinit var dispatcherProvider: DispatcherProvider

    @Inject internal lateinit var reminderActionExecutorProvider: dagger.Lazy<ReminderActionExecutor>

    @Inject internal lateinit var notificationFactory: StudyFlowNotificationFactory

    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + dispatcherProvider.default) }

    // `onStartCommand` runs on the main thread while `stopWhenTaskNoLongerNeedsIt`'s task
    // observation runs on `dispatcherProvider.default`, and both can call into `stopSession` — a
    // plain `mutableMapOf` is not safe under that concurrent access, so this is a
    // `ConcurrentHashMap` rather than the ordinary in-memory map every other piece of session
    // state in this module uses.
    private val sessions = java.util.concurrent.ConcurrentHashMap<Int, PlaybackSession>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when {
            intent?.action == ACTION_STOP -> stopSession(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
            else -> startSessionFrom(intent)
        }
        return START_NOT_STICKY
    }

    private fun startSessionFrom(intent: Intent?) {
        val reminderId = intent?.getStringExtra(EXTRA_REMINDER_ID)
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (reminderId == null || taskId == null || notificationId == null) {
            Log.w(TAG, "Alarm playback start is missing its reminder, task or notification id")
            stopIfIdle()
            return
        }

        promoteToForeground()
        if (sessions.containsKey(notificationId)) return
        sessions[notificationId] = start(reminderId, taskId, notificationId)
    }

    override fun onDestroy() {
        sessions.keys.toList().forEach { stopSession(it) }
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val notification =
            notificationFactory.alert(
                channel = StudyFlowNotificationChannel.ALARMS,
                title = "Alarm ringing",
                text = "Tap to open",
                contentIntent = null,
            )
        ServiceCompat.startForeground(
            this,
            FOREGROUND_NOTIFICATION_ID,
            notification,
            foregroundServiceType(),
        )
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }

    private fun start(
        reminderId: String,
        taskId: String,
        notificationId: Int,
    ): PlaybackSession {
        val audioManager = getSystemService(AudioManager::class.java)
        val attributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        val focusRequest =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .build()
        val focusResult = audioManager.requestAudioFocus(focusRequest)
        // An alarm rings regardless of the outcome — `AUDIOFOCUS_REQUEST_FAILED` (another app
        // holding a stronger, non-transient focus) must not silently swallow "wake me for the
        // exam", and `AUDIOFOCUS_REQUEST_DELAYED` never applies to a transient, non-exclusive
        // request like this one. The result is only used to decide whether to release focus later:
        // abandoning a request that was never granted is a harmless no-op, but logging here makes
        // the (intentional) "play anyway" choice visible rather than silently ignored.
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Alarm audio focus was not granted (result=$focusResult); ringing anyway")
        }

        val player = MediaPlayer().apply { setAudioAttributes(attributes) }

        val vibrator = vibratorService()
        vibrator?.vibrate(
            VibrationEffect.createWaveform(
                AlarmPlaybackPolicy.VIBRATION_PATTERN,
                AlarmPlaybackPolicy.VIBRATION_REPEAT_INDEX,
            ),
        )

        val job =
            serviceScope.launch {
                // The DataStore read, `setDataSource` (a content-resolver round trip for a
                // `content://` ringtone URI) and `prepare()` are all blocking I/O; running them
                // here — off `onStartCommand`'s main-thread caller — rather than with
                // `runBlocking` is what keeps a cold DataStore read from risking an ANR.
                runCatching {
                    val uri = ringtoneUri()
                    withContext(dispatcherProvider.io) {
                        player.setDataSource(this@AlarmPlaybackService, uri)
                        player.isLooping = true
                        val startVolume = AlarmPlaybackPolicy.volumeAt(Duration.ZERO)
                        player.setVolume(startVolume, startVolume)
                        player.prepare()
                        player.start()
                    }
                }.onFailure { Log.w(TAG, "Alarm playback could not start its ringtone", it) }

                val startUptime = AndroidElapsedRealtimeSource.uptime()
                launch { stopWhenTaskNoLongerNeedsIt(taskId, reminderId, notificationId) }
                while (true) {
                    val elapsed = AndroidElapsedRealtimeSource.uptime() - startUptime
                    player.setVolume(AlarmPlaybackPolicy.volumeAt(elapsed), AlarmPlaybackPolicy.volumeAt(elapsed))
                    if (AlarmPlaybackPolicy.hasTimedOut(elapsed)) {
                        onTimedOut(reminderId, taskId, notificationId)
                        return@launch
                    }
                    delay(AlarmPlaybackPolicy.TICK_INTERVAL)
                }
            }

        return PlaybackSession(player, vibrator, audioManager, focusRequest, job)
    }

    /** Marking the task done anywhere else in the app must silence this alarm too (issue #48). */
    private suspend fun stopWhenTaskNoLongerNeedsIt(
        taskId: String,
        reminderId: String,
        notificationId: Int,
    ) {
        taskRepository.observeTask(taskId).collect { task ->
            val stillNeeded =
                task != null && !task.isCompleted && !task.deleted &&
                    task.reminders.any { it.id == reminderId }
            if (!stillNeeded) {
                stopSession(notificationId)
            }
        }
    }

    private fun onTimedOut(
        reminderId: String,
        taskId: String,
        notificationId: Int,
    ) {
        serviceScope.launch {
            reminderActionExecutorProvider.get().execute(AlarmPlaybackPolicy.OUTCOME_AFTER_TIMEOUT, reminderId, taskId)
        }
        stopSession(notificationId)
    }

    private fun stopSession(notificationId: Int) {
        val session = sessions.remove(notificationId) ?: return
        session.job.cancel()
        runCatching { session.player.stop() }
        session.player.release()
        session.vibrator?.cancel()
        runCatching { session.audioManager.abandonAudioFocusRequest(session.focusRequest) }
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (sessions.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun ringtoneUri(): Uri {
        val configured = settingsStore.data.first().alarmRingtoneUri
        return configured
            .takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    }

    private fun vibratorService(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }

    private data class PlaybackSession(
        val player: MediaPlayer,
        val vibrator: Vibrator?,
        val audioManager: AudioManager,
        val focusRequest: AudioFocusRequest,
        val job: Job,
    )

    public companion object {
        /** Starts (or is a no-op for an already-ringing) alarm for one fired reminder. */
        public fun start(
            context: Context,
            reminderId: String,
            taskId: String,
            notificationId: Int,
        ) {
            val intent =
                Intent(context, AlarmPlaybackService::class.java).apply {
                    putExtra(EXTRA_REMINDER_ID, reminderId)
                    putExtra(EXTRA_TASK_ID, taskId)
                    putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                }
            context.startForegroundService(intent)
        }

        /** Stops the alarm for [notificationId] if it is currently ringing; a no-op otherwise. */
        public fun stop(
            context: Context,
            notificationId: Int,
        ) {
            context.startService(
                Intent(context, AlarmPlaybackService::class.java).apply {
                    action = ACTION_STOP
                    putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                },
            )
        }

        private const val FOREGROUND_NOTIFICATION_ID = 0x53_74_41_6c // "StAl", arbitrary but stable.
        private const val ACTION_STOP = "dev.studyflow.core.scheduling.action.STOP_ALARM"
        private const val EXTRA_NOTIFICATION_ID = "dev.studyflow.core.scheduling.NOTIFICATION_ID"
    }
}

private const val TAG: String = "AlarmPlayback"
