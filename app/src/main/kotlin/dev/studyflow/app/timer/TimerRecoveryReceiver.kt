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
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            recoveryCoordinator.recoverActiveSession(onComplete = pendingResult::finish)
        }
    }
}
