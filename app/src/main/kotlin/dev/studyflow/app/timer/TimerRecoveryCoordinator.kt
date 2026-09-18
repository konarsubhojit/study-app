package dev.studyflow.app.timer

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.studyflow.core.common.coroutines.ApplicationScope
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.time.SystemWallClock
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.TimeAnchor
import dev.studyflow.core.scheduling.AndroidElapsedRealtimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

@Singleton
internal class TimerRecoveryCoordinator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val sessionRepository: SessionRepository,
        @ApplicationScope private val applicationScope: CoroutineScope,
        private val logger: AppLogger,
    ) {
        private val fallbackBootIdLock = Any()

        fun recoverActiveSession(onComplete: () -> Unit = {}): Job =
            applicationScope.launch {
                try {
                    sessionRepository.reconcile(
                        eventId = UUID.randomUUID().toString(),
                        now = currentAnchor(),
                    )
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
        }
    }
