package dev.studyflow.app.timer

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.studyflow.app.R
import dev.studyflow.app.navigation.StudyFlowDeepLinks
import dev.studyflow.app.navigation.TimerRoute
import dev.studyflow.core.common.coroutines.ApplicationScope
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.time.SystemWallClock
import dev.studyflow.core.datastore.ActiveTimer
import dev.studyflow.core.datastore.ActiveTimerStore
import dev.studyflow.core.domain.session.RecoveredTimerAnchor
import dev.studyflow.core.domain.session.SessionCommandResult
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.domain.timer.TimerAccuracyReporter
import dev.studyflow.core.domain.timer.TimerAccuracySample
import dev.studyflow.core.domain.timer.TimerAccuracySource
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.TimeAnchor
import dev.studyflow.core.notifications.ChronometerPresentation
import dev.studyflow.core.notifications.StudyFlowNotificationChannel
import dev.studyflow.core.notifications.StudyFlowNotificationFactory
import dev.studyflow.core.notifications.StudyFlowNotifier
import dev.studyflow.core.notifications.StudyFlowPendingIntents
import dev.studyflow.core.scheduling.AndroidElapsedRealtimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@Singleton
internal class TimerRecoveryCoordinator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val sessionRepository: SessionRepository,
        private val activeTimerStore: ActiveTimerStore,
        private val notificationFactory: StudyFlowNotificationFactory,
        private val notifier: StudyFlowNotifier,
        @ApplicationScope private val applicationScope: CoroutineScope,
        private val logger: AppLogger,
        private val accuracyReporter: TimerAccuracyReporter,
    ) {
        private val fallbackBootIdLock = Any()

        fun recoverActiveSession(onComplete: () -> Unit = {}): Job = recoverAfterBoot(onComplete)

        fun recoverAfterBoot(onComplete: () -> Unit = {}): Job =
            applicationScope.launch {
                try {
                    val now = currentAnchor()
                    val timer = activeTimerStore.activeTimer.first()
                    restoreDeviceProtectedTimerNotification(timer, now)
                    val result =
                        sessionRepository.reconcile(
                            eventId = UUID.randomUUID().toString(),
                            now = now,
                            recoveredAnchor = timer?.previousBootAnchor(now),
                        )
                    updateForegroundAfterRecovery(result)
                    reportRecoveryAccuracy(result, timer, now)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (
                    @Suppress("TooGenericExceptionCaught") failure: Throwable,
                ) {
                    logger.warning(TAG, "Active-session recovery failed", failure)
                } finally {
                    onComplete()
                }
            }

        fun recoverAfterClockChange(onComplete: () -> Unit = {}): Job =
            applicationScope.launch {
                try {
                    correctDeviceProtectedTimerAnchor(currentAnchor())
                    refreshTimerForegroundService()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (
                    @Suppress("TooGenericExceptionCaught") failure: Throwable,
                ) {
                    logger.warning(TAG, "Active-session clock-change recovery failed", failure)
                } finally {
                    onComplete()
                }
            }

        private fun restoreDeviceProtectedTimerNotification(
            timer: ActiveTimer?,
            now: TimeAnchor,
        ) {
            timer ?: return
            if (timer.bootId == now.bootId.value) return
            val notification =
                notificationFactory.ongoingChronometer(
                    title = context.getString(R.string.timer_notification_title),
                    text = context.getString(R.string.timer_notification_reboot_recovered),
                    startedAtEpochMillis = now.wallClock.toEpochMilliseconds(),
                    contentIntent = openTimerIntent(),
                    chronometer = ChronometerPresentation(usesChronometer = false),
                )
            notifier.post(TIMER_NOTIFICATION_ID, StudyFlowNotificationChannel.STUDY_TIMER, notification)
        }

        private fun ActiveTimer.previousBootAnchor(now: TimeAnchor): RecoveredTimerAnchor? =
            takeIf { bootId != now.bootId.value }?.let {
                RecoveredTimerAnchor(
                    sessionId = sessionId,
                    openedAt =
                        TimeAnchor(
                            uptime = uptimeMillis.milliseconds,
                            wallClock = Instant.fromEpochMilliseconds(wallClockEpochMillis),
                            bootId = BootId(bootId),
                        ),
                )
            }

        private suspend fun correctDeviceProtectedTimerAnchor(now: TimeAnchor) {
            val timer = activeTimerStore.activeTimer.first() ?: return
            if (timer.bootId != now.bootId.value) return
            val elapsedSinceOpen =
                (now.uptime - timer.uptimeMillis.milliseconds).coerceAtLeast(Duration.ZERO)
            activeTimerStore.set(
                timer.copy(
                    wallClockEpochMillis = (now.wallClock - elapsedSinceOpen).toEpochMilliseconds(),
                ),
            )
        }

        private fun updateForegroundAfterRecovery(result: SessionCommandResult) {
            when (result) {
                is SessionCommandResult.Applied -> {
                    refreshTimerForegroundService()
                }

                is SessionCommandResult.Unchanged -> {
                    when (result.state) {
                        TimerState.Idle, is TimerState.Stopped -> notifier.cancel(TIMER_NOTIFICATION_ID)
                        is TimerState.Paused, is TimerState.Running -> refreshTimerForegroundService()
                    }
                }

                is SessionCommandResult.Failed,
                is SessionCommandResult.Rejected,
                -> {
                    return
                }
            }
        }

        private fun reportRecoveryAccuracy(
            result: SessionCommandResult,
            timer: ActiveTimer?,
            now: TimeAnchor,
        ) {
            val applied = result as? SessionCommandResult.Applied ?: return
            timer ?: return
            if (timer.bootId == now.bootId.value) return
            val measuredGap =
                (now.wallClock - Instant.fromEpochMilliseconds(timer.wallClockEpochMillis)).coerceAtLeast(Duration.ZERO)
            accuracyReporter.report(
                TimerAccuracySample(
                    sessionId = applied.session.id,
                    source = TimerAccuracySource.REBOOT_RECOVERY,
                    expectedElapsed = Duration.ZERO,
                    measuredElapsed = measuredGap,
                ),
            )
        }

        private fun refreshTimerForegroundService() {
            try {
                ContextCompat.startForegroundService(
                    context,
                    TimerForegroundService.refreshIntent(context),
                )
            } catch (failure: IllegalStateException) {
                logger.warning(
                    tag = TAG,
                    message = "Timer foreground service start was deferred by Android",
                    throwable = failure,
                )
            }
        }

        private fun openTimerIntent() =
            StudyFlowPendingIntents.activity(
                context = context,
                requestCode = REQUEST_OPEN,
                deepLink = StudyFlowDeepLinks.uriFor(TimerRoute(openRunningTimer = true)),
            )

        private suspend fun currentAnchor(): TimeAnchor {
            val uptime = AndroidElapsedRealtimeSource.uptime()
            return TimeAnchor(
                uptime = uptime,
                wallClock = SystemWallClock.now(),
                bootId = currentBootId(uptime),
            )
        }

        private suspend fun currentBootId(uptime: Duration): BootId =
            runCatching {
                BootId(Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString())
            }.getOrElse { fallbackBootId(uptime) }

        /**
         * Rare fallback for devices where BOOT_COUNT is unavailable. The id is persisted so two
         * short-lived boot-recovery processes agree within the same boot, and rotated when
         * elapsedRealtime moves backwards.
         */
        @SuppressLint("ApplySharedPref")
        private suspend fun fallbackBootId(uptime: Duration): BootId =
            withContext(Dispatchers.IO) {
                synchronized(fallbackBootIdLock) {
                    val prefs = context.getSharedPreferences(FALLBACK_BOOT_PREFS, Context.MODE_PRIVATE)
                    val uptimeMillis = uptime.inWholeMilliseconds
                    val storedUptimeMillis = prefs.getLong(KEY_UPTIME_MILLIS, Long.MIN_VALUE)
                    val storedBootId = prefs.getString(KEY_BOOT_ID, null)
                    val bootId =
                        storedBootId
                            ?.takeIf { it.isNotBlank() && storedUptimeMillis <= uptimeMillis }
                            ?: "unknown-${UUID.randomUUID()}"

                    // commit() is intentional: this value must be durable before other short-lived
                    // boot-recovery processes read it back within the same boot.
                    prefs.edit(commit = true) {
                        putString(KEY_BOOT_ID, bootId)
                        putLong(KEY_UPTIME_MILLIS, uptimeMillis)
                    }
                    BootId(bootId)
                }
            }

        private companion object {
            const val TAG = "TimerRecovery"
            const val FALLBACK_BOOT_PREFS = "timer_recovery_boot"
            const val KEY_BOOT_ID = "boot_id"
            const val KEY_UPTIME_MILLIS = "uptime_millis"
            const val REQUEST_OPEN = 33_104
        }
    }
