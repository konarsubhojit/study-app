package dev.studyflow.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.studyflow.app.timer.TimerRecoveryCoordinator
import dev.studyflow.core.common.coroutines.ApplicationScope
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.CrashReporter
import dev.studyflow.core.scheduling.ReminderIntegrityCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class StudyFlowApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var logger: AppLogger

    @Inject lateinit var crashReporter: CrashReporter

    @Inject internal lateinit var timerRecoveryCoordinator: TimerRecoveryCoordinator

    @Inject internal lateinit var reminderIntegrityCoordinator: ReminderIntegrityCoordinator

    @Inject
    @ApplicationScope
    internal lateinit var applicationScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    override fun onCreate() {
        super.onCreate()
        crashReporter.initialize(
            enabled = BuildConfig.CRASH_REPORTING_ENABLED,
            optedOut = BuildConfig.CRASH_REPORTING_OPTED_OUT,
        )
        logger.info("Application", "StudyFlow started")
        timerRecoveryCoordinator.recoverActiveSession()
        applicationScope.launch {
            reminderIntegrityCoordinator.checkNow()
        }
    }
}
