package dev.studyflow.app.timer

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.studyflow.core.common.coroutines.ApplicationScope
import dev.studyflow.core.common.time.SystemWallClock
import dev.studyflow.core.domain.session.SessionRepository
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.TimeAnchor
import dev.studyflow.core.scheduling.AndroidElapsedRealtimeSource
import kotlinx.coroutines.CoroutineScope
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
    ) {
        fun recoverActiveSession() {
            applicationScope.launch {
                sessionRepository.reconcile(
                    eventId = UUID.randomUUID().toString(),
                    now = currentAnchor(),
                )
            }
        }

        private fun currentAnchor(): TimeAnchor =
            TimeAnchor(
                uptime = AndroidElapsedRealtimeSource.uptime(),
                wallClock = SystemWallClock.now(),
                bootId = currentBootId(),
            )

        private fun currentBootId(): BootId =
            BootId(Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).toString())
    }
