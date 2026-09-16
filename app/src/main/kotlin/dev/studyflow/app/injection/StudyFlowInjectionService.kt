package dev.studyflow.app.injection

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import dev.studyflow.core.common.coroutines.ApplicationScope
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import javax.inject.Inject

@AndroidEntryPoint
class StudyFlowInjectionService : Service() {
    @Inject lateinit var logger: AppLogger

    @Inject lateinit var dispatcherProvider: DispatcherProvider

    @ApplicationScope @Inject
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        check(applicationScope.coroutineContext[Job] != null)
        logger.debug("Service", "Dependency graph ready on $dispatcherProvider")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
