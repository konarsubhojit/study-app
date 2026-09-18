package dev.studyflow.core.scheduling

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.domain.tasks.TaskRepository
import dev.studyflow.core.model.StudyTask
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Reconciles persisted reminders with platform scheduling and honestly catches up anything late.
 */
public class ReminderIntegrityCoordinator(
    private val taskRepository: TaskRepository,
    private val schedulingService: ReminderSchedulingService,
    private val clock: Clock,
    private val logger: AppLogger,
    private val deliverReminder: suspend (reminderId: String, taskId: String) -> Unit,
) {
    public constructor(
        taskRepository: TaskRepository,
        schedulingService: ReminderSchedulingService,
        deliveryCoordinator: ReminderDeliveryCoordinator,
        clock: Clock,
        logger: AppLogger,
    ) : this(
        taskRepository = taskRepository,
        schedulingService = schedulingService,
        clock = clock,
        logger = logger,
        deliverReminder = deliveryCoordinator::deliver,
    )

    public suspend fun checkNow() {
        reconcile(taskRepository.observeTasks().first())
    }

    public suspend fun reconcile(tasks: Collection<StudyTask>): ReminderIntegrityReport {
        val now = clock.now()
        val results = schedulingService.rescheduleAll(tasks)
        val scheduled = results.filterIsInstance<ReminderScheduleResult.Scheduled>()
        val overdue = scheduled.filter { it.plan.isOverdueAt(now) }
        overdue.forEach { deliverReminder(it.plan.reminderId, it.plan.taskId) }

        val report =
            ReminderIntegrityReport(
                scheduledCount = scheduled.size,
                notScheduledCount = results.size - scheduled.size,
                overdueCount = overdue.size,
                degradedCount = scheduled.count { it.plan.isDegraded },
            )
        if (report.hasAnomaly) {
            logger.warning(
                TAG,
                "reminder_integrity_anomaly scheduled=${report.scheduledCount} " +
                    "notScheduled=${report.notScheduledCount} overdue=${report.overdueCount} " +
                    "degraded=${report.degradedCount}",
            )
        } else {
            logger.info(TAG, "reminder_integrity_ok scheduled=${report.scheduledCount}")
        }
        return report
    }

    private companion object {
        const val TAG = "ReminderIntegrity"
    }
}

public data class ReminderIntegrityReport(
    val scheduledCount: Int,
    val notScheduledCount: Int,
    val overdueCount: Int,
    val degradedCount: Int,
) {
    public val hasAnomaly: Boolean
        get() = notScheduledCount > 0 || overdueCount > 0 || degradedCount > 0
}

public class ReminderIntegrityScheduler(
    private val workManager: WorkManager,
) {
    public constructor(context: Context) : this(WorkManager.getInstance(context))

    public fun ensureScheduled() {
        val request =
            PeriodicWorkRequestBuilder<ReminderIntegrityWorker>(
                INTEGRITY_INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val WORK_NAME = "studyflow.reminder.integrity"
        const val INTEGRITY_INTERVAL_HOURS = 24L
    }
}

@HiltWorker
public class ReminderIntegrityWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted parameters: WorkerParameters,
        private val coordinator: ReminderIntegrityCoordinator,
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            coordinator.checkNow()
            return Result.success()
        }
    }
