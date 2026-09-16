package dev.studyflow.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.studyflow.app.logging.AndroidLogging
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.CrashReporter
import javax.inject.Inject

@HiltAndroidApp
class StudyFlowApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var logger: AppLogger

    @Inject lateinit var crashReporter: CrashReporter

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    override fun onCreate() {
        super.onCreate()
        AndroidLogging.install(debug = BuildConfig.DEBUG)
        crashReporter.initialize(
            enabled = BuildConfig.CRASH_REPORTING_ENABLED,
            optedOut = BuildConfig.CRASH_REPORTING_OPTED_OUT,
        )
        logger.info("Application", "StudyFlow started")
    }
}
