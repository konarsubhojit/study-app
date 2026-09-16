package dev.studyflow.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.studyflow.core.common.coroutines.ApplicationScope
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

@HiltWorker
class InjectedStudyWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val logger: AppLogger,
        private val dispatcherProvider: DispatcherProvider,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result =
            withContext(dispatcherProvider.default) {
                check(applicationScope.coroutineContext[Job] != null)
                logger.debug("Worker", "Dependency graph ready")
                Result.success()
            }
    }
