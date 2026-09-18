package dev.studyflow.app.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
internal class TimerRecoveryReceiver : BroadcastReceiver() {
    @Inject
    internal lateinit var recoveryCoordinator: TimerRecoveryCoordinator

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pendingResult =
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                -> goAsync().also { recoveryCoordinator.recoverAfterBoot(onComplete = it::finish) }

                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                -> goAsync().also { recoveryCoordinator.recoverAfterClockChange(onComplete = it::finish) }

                else -> null
            }
        pendingResult ?: return
    }
}
