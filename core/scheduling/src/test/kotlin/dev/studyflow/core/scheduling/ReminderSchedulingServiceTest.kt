package dev.studyflow.core.scheduling

import dev.studyflow.core.domain.reminder.ReminderDelivery
import dev.studyflow.core.domain.reminder.ReminderPlan
import dev.studyflow.core.domain.reminder.SchedulingCapabilities
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.ReminderPrecision
import dev.studyflow.core.model.StudyTask
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@DisplayName("ReminderSchedulingService")
class ReminderSchedulingServiceTest {
    private val now = Instant.parse("2026-03-02T06:00:00Z")
    private var capabilities = SchedulingCapabilities()
    private val platform = RecordingPlatformScheduler()
    private val service = ReminderSchedulingService({ capabilities }, platform) { now }

    @Test
    fun `exact reminders use exact alarms when permission is granted`() {
        val result = service.schedule(task(ReminderPrecision.EXACT))

        assertEquals(listOf("cancel:reminder-task-1", "exact:reminder-task-1"), platform.calls)
        assertEquals(ReminderDelivery.EXACT, result.scheduledPlan().delivery)
        assertNull(result.scheduled().banner)
    }

    @Test
    fun `exact reminders downgrade to inexact with rationale and banner when permission is denied`() {
        capabilities = SchedulingCapabilities(canScheduleExactAlarms = false)

        val result = service.schedule(task(ReminderPrecision.EXACT))

        assertEquals(listOf("cancel:reminder-task-1", "inexact:reminder-task-1"), platform.calls)
        assertEquals(ReminderDelivery.INEXACT, result.scheduledPlan().delivery)
        assertNotNull(result.scheduled().permissionRationale)
        assertNotNull(result.scheduled().banner)
    }

    @Test
    fun `alarm reminders use the alarm-clock platform primitive`() {
        val result = service.schedule(task(ReminderPrecision.ALARM))

        assertEquals(listOf("cancel:reminder-task-1", "alarm:reminder-task-1"), platform.calls)
        assertEquals(ReminderDelivery.ALARM_CLOCK, result.scheduledPlan().delivery)
    }

    @Test
    fun `revoking exact alarm permission mid-flight replaces the exact registration with inexact`() {
        service.schedule(task(ReminderPrecision.EXACT))
        capabilities = SchedulingCapabilities(canScheduleExactAlarms = false)

        val result = service.schedule(task(ReminderPrecision.EXACT))

        assertEquals(
            listOf(
                "cancel:reminder-task-1",
                "exact:reminder-task-1",
                "cancel:reminder-task-1",
                "inexact:reminder-task-1",
            ),
            platform.calls,
        )
        assertEquals(ReminderDelivery.INEXACT, result.scheduledPlan().delivery)
    }

    @Test
    fun `exact alarm race fallback is surfaced in the scheduling result`() {
        platform.nextExactOutcome = PlatformScheduleOutcome.FALLBACK_TO_INEXACT

        val result = service.schedule(task(ReminderPrecision.EXACT))

        assertEquals(ReminderDelivery.INEXACT, result.scheduledPlan().delivery)
        assertNotNull(result.scheduled().permissionRationale)
        assertNotNull(result.scheduled().banner)
    }

    @Test
    fun `repeated rescheduling never stacks duplicate platform registrations`() {
        repeat(3) { service.schedule(task(ReminderPrecision.GENTLE)) }

        assertEquals(3, platform.calls.count { it == "cancel:reminder-task-1" })
        assertEquals(3, platform.calls.count { it == "inexact:reminder-task-1" })
        assertEquals(setOf("reminder-task-1"), platform.activeReminderIds)
    }

    @Test
    fun `rescheduleAll schedules each outstanding task with fresh capabilities`() {
        val results =
            service.rescheduleAll(
                listOf(
                    task(ReminderPrecision.GENTLE, id = "gentle"),
                    task(ReminderPrecision.EXACT, id = "exact"),
                ),
            )

        assertEquals(
            listOf(
                "cancel:reminder-gentle",
                "inexact:reminder-gentle",
                "cancel:reminder-exact",
                "exact:reminder-exact",
            ),
            platform.calls,
        )
        assertEquals(
            listOf(ReminderDelivery.INEXACT, ReminderDelivery.EXACT),
            results.map { it.scheduledPlan().delivery },
        )
    }

    @Test
    fun `completed tasks cancel any existing reminder instead of scheduling`() {
        val result = service.schedule(task(ReminderPrecision.GENTLE).copy(completedAt = now))

        assertEquals(listOf("cancel:reminder-task-1"), platform.calls)
        assertTrue(result is ReminderScheduleResult.NotScheduled)
    }

    @Test
    fun `explicit cancel removes exact alarm and inexact work for the reminder id`() {
        service.cancel("reminder-task-1")

        assertEquals(listOf("cancel:reminder-task-1"), platform.calls)
        assertTrue(platform.activeReminderIds.isEmpty())
    }

    private fun ReminderScheduleResult.scheduled(): ReminderScheduleResult.Scheduled =
        this as ReminderScheduleResult.Scheduled

    private fun ReminderScheduleResult.scheduledPlan(): ReminderPlan = scheduled().plan

    private fun task(
        precision: ReminderPrecision,
        id: String = "task-1",
    ): StudyTask =
        StudyTask(
            id = id,
            title = "Revise chapter 4",
            dueAt = LocalDateTime(2026, 3, 2, 8, 0),
            timeZone = TimeZone.of("Europe/London"),
            reminder = Reminder(id = "reminder-$id", precision = precision),
        )

    private class RecordingPlatformScheduler : ReminderPlatformScheduler {
        val calls = mutableListOf<String>()
        val activeReminderIds = mutableSetOf<String>()
        var nextExactOutcome = PlatformScheduleOutcome.SCHEDULED

        override fun scheduleInexact(plan: ReminderPlan): PlatformScheduleOutcome {
            calls += "inexact:${plan.reminderId}"
            activeReminderIds += plan.reminderId
            return PlatformScheduleOutcome.SCHEDULED
        }

        override fun scheduleExact(plan: ReminderPlan): PlatformScheduleOutcome {
            calls += "exact:${plan.reminderId}"
            activeReminderIds += plan.reminderId
            return nextExactOutcome.also {
                nextExactOutcome = PlatformScheduleOutcome.SCHEDULED
            }
        }

        override fun scheduleAlarmClock(plan: ReminderPlan): PlatformScheduleOutcome {
            calls += "alarm:${plan.reminderId}"
            activeReminderIds += plan.reminderId
            return PlatformScheduleOutcome.SCHEDULED
        }

        override fun cancel(reminderId: String) {
            calls += "cancel:$reminderId"
            activeReminderIds -= reminderId
        }
    }
}
