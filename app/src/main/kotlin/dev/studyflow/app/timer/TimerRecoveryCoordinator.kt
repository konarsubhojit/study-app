package dev.studyflow.app.timer

import android.content.Context
import android.provider.Settings
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class TimerRecoveryCoordinator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val sessionRepository: SessionRepository,
        @ApplicationScope private val applicationScope: CoroutineScope,
        private val logger: AppLogger,
    ) {
        /**
         * Conservative fallback for rare devices where BOOT_COUNT is unavailable: a fresh process id
         * makes old monotonic anchors unverifiable instead of pretending they are from this boot.
         */
        private val fallbackBootId = BootId("unknown-${UUID.randomUUID()}")

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

        private fun currentAnchor(): TimeAnchor =
            TimeAnchor(
                uptime = AndroidElapsedRealtimeSource.uptime(),
                wallClock = SystemWallClock.now(),
                bootId = currentBootId(),
            )

        private fun currentBootId(): BootId =
            runCatching {
                BootId(Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString())
            }.getOrDefault(fallbackBootId)

        private companion object {
            const val TAG = "TimerRecovery"
        }
    }
