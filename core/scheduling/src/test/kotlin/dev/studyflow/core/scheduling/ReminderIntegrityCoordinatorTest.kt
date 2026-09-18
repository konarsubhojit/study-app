package dev.studyflow.core.scheduling

import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.domain.reminder.SchedulingCapabilities
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.ReminderPrecision
import dev.studyflow.core.model.ReminderTrigger
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.testing.data.FakeTaskRepository
import dev.studyflow.core.testing.logging.RecordingAppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@DisplayName("ReminderIntegrityCoordinator")
class ReminderIntegrityCoordinatorTest {
    private val taskRepository = FakeTaskRepository(now = NOW)
    private val platform = RecordingPlatformScheduler()
    private var capabilities = SchedulingCapabilities()
    private val schedulingService = ReminderSchedulingService({ capabilities }, platform) { NOW }
    private val logger = RecordingAppLogger()
    private val deliveries = mutableListOf<String>()
    private val coordinator =
        ReminderIntegrityCoordinator(
            taskRepository = taskRepository,
            schedulingService = schedulingService,
            clock = Clock { NOW },
            logger = logger,
            deliverReminder = { reminderId, taskId -> deliveries += "$reminderId:$taskId" },
        )

    @Test
    fun `reconcile re-arms all pending reminders`() =
        runTest {
            taskRepository.save(task("future", LocalDateTime(2026, 3, 2, 8, 0)))

            val report = coordinator.check()

            assertEquals(listOf("cancel:reminder-future", "inexact:reminder-future"), platform.calls)
            assertEquals(1, report.scheduledCount)
            assertEquals(0, report.overdueCount)
            assertTrue(deliveries.isEmpty())
        }

    @Test
    fun `reconcile immediately delivers overdue reminders and reports an anomaly`() =
        runTest {
            taskRepository.save(task("late", LocalDateTime(2026, 3, 2, 7, 0), leadTime = 2.hours))

            val report = coordinator.check()

            assertEquals(listOf("reminder-late:late"), deliveries)
            assertEquals(1, report.overdueCount)
            assertTrue(report.hasAnomaly)
            assertEquals(listOf("reminder_integrity_anomaly"), logger.messages.map { it.message.substringBefore(' ') })
        }

    @Test
    fun `degraded schedules are counted for telemetry`() =
        runTest {
            capabilities = SchedulingCapabilities(canScheduleExactAlarms = false)
            taskRepository.save(task("exact", LocalDateTime(2026, 3, 2, 8, 0), ReminderPrecision.EXACT))

            val report = coordinator.check()

            assertEquals(1, report.degradedCount)
            assertTrue(report.hasAnomaly)
        }

    private suspend fun ReminderIntegrityCoordinator.check(): ReminderIntegrityReport {
        return reconcile(taskRepository.observeTasks().first())
    }

    private fun task(
        id: String,
        dueAt: LocalDateTime,
        precision: ReminderPrecision = ReminderPrecision.GENTLE,
        leadTime: Duration = Duration.ZERO,
    ): StudyTask =
        StudyTask(
            id = id,
            title = "Revise",
            dueAt = dueAt,
            timeZone = TimeZone.UTC,
            reminders =
                listOf(
                    Reminder(
                        id = "reminder-$id",
                        taskId = id,
                        precision = precision,
                        trigger = ReminderTrigger.BeforeDue(leadTime),
                    ),
                ),
            updatedAt = NOW,
        )

    private class RecordingPlatformScheduler : ReminderPlatformScheduler {
        val calls = mutableListOf<String>()

        override fun scheduleInexact(plan: dev.studyflow.core.domain.reminder.ReminderPlan): PlatformScheduleOutcome {
            calls += "inexact:${plan.reminderId}"
            return PlatformScheduleOutcome.SCHEDULED
        }

        override fun scheduleExact(plan: dev.studyflow.core.domain.reminder.ReminderPlan): PlatformScheduleOutcome {
            calls += "exact:${plan.reminderId}"
            return PlatformScheduleOutcome.SCHEDULED
        }

        override fun scheduleAlarmClock(plan: dev.studyflow.core.domain.reminder.ReminderPlan): PlatformScheduleOutcome {
            calls += "alarm:${plan.reminderId}"
            return PlatformScheduleOutcome.SCHEDULED
        }

        override fun cancel(reminderId: String) {
            calls += "cancel:$reminderId"
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-02T06:00:00Z")
    }
}
