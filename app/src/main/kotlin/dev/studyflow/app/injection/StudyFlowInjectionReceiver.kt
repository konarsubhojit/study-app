package dev.studyflow.app.injection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.studyflow.core.common.logging.AppLogger
import javax.inject.Inject

@AndroidEntryPoint
class StudyFlowInjectionReceiver : BroadcastReceiver() {
    @Inject lateinit var logger: AppLogger

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        logger.debug("Receiver", "Dependency graph ready")
    }
}
