package dev.studyflow.core.domain.reminder

import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.RecurrenceRule
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.ReminderPrecision
import dev.studyflow.core.model.ReminderTrigger
import dev.studyflow.core.model.SnoozeState
import dev.studyflow.core.model.StudyTask
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@DisplayName("ReminderScheduler")
class ReminderSchedulerTest {
    private val london = TimeZone.of("Europe/London")
    private val now = Instant.parse("2026-03-02T06:00:00Z")

    @Nested
    @DisplayName("choosing a delivery mechanism")
    inner class Delivery {
        @Test
        fun `a gentle reminder is batched`() {
            val plan = plan(ReminderPrecision.GENTLE)

            assertEquals(ReminderDelivery.INEXACT, plan.delivery)
            assertFalse(plan.isDegraded)
        }

        @Test
        fun `an exact reminder uses an exact alarm when permitted`() {
            val plan = plan(ReminderPrecision.EXACT)

            assertEquals(ReminderDelivery.EXACT, plan.delivery)
            assertFalse(plan.isDegraded)
        }

        @Test
        fun `an alarm reminder uses the alarm clock API`() {
            val plan = plan(ReminderPrecision.ALARM)

            assertEquals(ReminderDelivery.ALARM_CLOCK, plan.delivery)
            assertFalse(plan.isDegraded)
        }
    }

    @Nested
    @DisplayName("degrading visibly")
    inner class Degradation {
        @Test
        fun `an exact reminder falls back to inexact and says so`() {
            val plan =
                plan(
                    ReminderPrecision.EXACT,
                    SchedulingCapabilities(canScheduleExactAlarms = false),
                )

            assertEquals(ReminderDelivery.INEXACT, plan.delivery)
            assertTrue(ReminderDegradation.EXACT_ALARMS_DENIED in plan.degradations)
        }

        @Test
        fun `an alarm still works without the exact-alarm permission`() {
            val plan =
                plan(
                    ReminderPrecision.ALARM,
                    SchedulingCapabilities(canScheduleExactAlarms = false),
                )

            assertEquals(ReminderDelivery.ALARM_CLOCK, plan.delivery)
            assertFalse(
                ReminderDegradation.EXACT_ALARMS_DENIED in plan.degradations,
                "setAlarmClock does not need SCHEDULE_EXACT_ALARM, so nothing was lost",
            )
        }

        @Test
        fun `an alarm without full-screen intent is downgraded to a notification`() {
            val plan =
                plan(
                    ReminderPrecision.ALARM,
                    SchedulingCapabilities(canUseFullScreenIntent = false),
                )

            assertTrue(ReminderDegradation.FULL_SCREEN_INTENT_DENIED in plan.degradations)
        }

        @Test
        fun `blocked notifications are reported for every precision`() {
            val capabilities = SchedulingCapabilities(notificationsEnabled = false)

            ReminderPrecision.entries.forEach { precision ->
                val plan = plan(precision, capabilities)
                assertTrue(
                    ReminderDegradation.NOTIFICATIONS_DENIED in plan.degradations,
                    "$precision should report blocked notifications",
                )
            }
        }

        @Test
        fun `battery optimisation is reported for time-critical reminders only`() {
            val capabilities = SchedulingCapabilities(batteryOptimised = true)

            assertFalse(
                ReminderDegradation.BATTERY_OPTIMISED in plan(ReminderPrecision.GENTLE, capabilities).degradations,
            )
            assertTrue(
                ReminderDegradation.BATTERY_OPTIMISED in plan(ReminderPrecision.EXACT, capabilities).degradations,
            )
        }

        @Test
        fun `everything denied at once reports every problem`() {
            val plan =
                plan(
                    ReminderPrecision.ALARM,
                    SchedulingCapabilities(
                        canScheduleExactAlarms = false,
                        notificationsEnabled = false,
                        canUseAlarmClock = false,
                        canUseFullScreenIntent = false,
                        batteryOptimised = true,
                    ),
                )

            assertEquals(ReminderDelivery.INEXACT, plan.delivery)
            assertEquals(ReminderDegradation.entries.toSet(), plan.degradations)
        }
    }

    @Nested
    @DisplayName("choosing when to fire")
    inner class Timing {
        @Test
        fun `lead time moves the trigger earlier than the due time`() {
            val plan = plan(ReminderPrecision.GENTLE, leadTime = 15.minutes)

            assertEquals("2026-03-02T08:00", plan.occurrenceAt.toLocalDateTime(london).toString())
            assertEquals("2026-03-02T07:45", plan.triggerAt.toLocalDateTime(london).toString())
        }

        @Test
        fun `an occurrence whose notification is still in the future is not skipped`() {
            // 07:50 local: the 08:00 task is imminent, but its 15-minute warning already passed.
            val plan =
                plan(
                    precision = ReminderPrecision.GENTLE,
                    leadTime = 15.minutes,
                    now = Instant.parse("2026-03-02T07:50:00Z"),
                )

            assertEquals(
                "2026-03-02T08:00",
                plan.occurrenceAt.toLocalDateTime(london).toString(),
                "the reminder is late, not irrelevant; the user still needs to be told",
            )
            assertTrue(plan.isOverdueAt(Instant.parse("2026-03-02T07:50:00Z")))
        }

        @Test
        fun `a snooze postpones this delivery without moving the occurrence`() {
            val snoozedUntil = Instant.parse("2026-03-02T08:10:00Z")
            val task =
                task().copy(
                    reminders =
                        listOf(
                            Reminder(
                                id = "reminder-1",
                                taskId = "task-1",
                                trigger = ReminderTrigger.BeforeDue(),
                                snooze = SnoozeState(until = snoozedUntil),
                            ),
                        ),
                )

            val plan = ReminderScheduler.plan(task, SchedulingCapabilities(), now).single()

            assertEquals("2026-03-02T08:00", plan.occurrenceAt.toLocalDateTime(london).toString())
            assertEquals(snoozedUntil, plan.triggerAt)
        }

        @Test
        fun `an absolute trigger fires at the instant the user picked, whatever the due time`() {
            val ringAt = Instant.parse("2026-03-02T07:00:00Z")
            val task =
                task().copy(
                    reminders =
                        listOf(
                            Reminder(
                                id = "reminder-1",
                                taskId = "task-1",
                                trigger = ReminderTrigger.AtInstant(ringAt, london),
                                precision = ReminderPrecision.ALARM,
                            ),
                        ),
                )

            val plan = ReminderScheduler.plan(task, SchedulingCapabilities(), now).single()

            assertEquals(ringAt, plan.triggerAt)
            assertEquals("2026-03-02T08:00", plan.occurrenceAt.toLocalDateTime(london).toString())
        }

        @Test
        fun `a recurring reminder rolls on to the next occurrence`() {
            val plan =
                plan(
                    precision = ReminderPrecision.GENTLE,
                    recurrence = RecurrenceRule(RecurrenceFrequency.DAILY),
                    now = Instant.parse("2026-03-04T12:00:00Z"),
                )

            assertEquals("2026-03-05T08:00", plan.occurrenceAt.toLocalDateTime(london).toString())
        }
    }

    @Nested
    @DisplayName("declining to schedule")
    inner class NothingToDo {
        @Test
        fun `a completed task is not scheduled`() {
            val task = task().copy(completedAt = now)

            assertTrue(ReminderScheduler.plan(task, SchedulingCapabilities(), now).isEmpty())
        }

        @Test
        fun `a deleted task is not scheduled`() {
            val task = task().copy(deleted = true)

            assertTrue(ReminderScheduler.plan(task, SchedulingCapabilities(), now).isEmpty())
        }

        @Test
        fun `a task with no reminder is not scheduled`() {
            val task = task().copy(reminders = emptyList())

            assertTrue(ReminderScheduler.plan(task, SchedulingCapabilities(), now).isEmpty())
        }

        @Test
        fun `a one-off reminder in the past is not rescheduled`() {
            val plans =
                ReminderScheduler.plan(
                    task = task(),
                    capabilities = SchedulingCapabilities(),
                    now = Instant.parse("2026-04-01T00:00:00Z"),
                )

            assertTrue(plans.isEmpty())
        }

        @Test
        fun `an absolute reminder that already fired is not fired again`() {
            val ringAt = Instant.parse("2026-03-02T07:00:00Z")
            val task =
                task().copy(
                    reminders =
                        listOf(
                            Reminder(
                                id = "reminder-alarm",
                                taskId = "task-1",
                                trigger = ReminderTrigger.AtInstant(ringAt, london),
                                precision = ReminderPrecision.ALARM,
                                lastFiredAt = ringAt,
                            ),
                        ),
                )

            assertTrue(
                ReminderScheduler.plan(task, SchedulingCapabilities(), Instant.parse("2026-03-02T08:30:00Z")).isEmpty(),
            )
        }
    }

    @Nested
    @DisplayName("re-arming after boot")
    inner class ReArm {
        @Test
        fun `planAll returns only the tasks that still need an alarm`() {
            val tasks =
                listOf(
                    task(id = "a"),
                    task(id = "b").copy(completedAt = now),
                    task(id = "c", recurrence = RecurrenceRule(RecurrenceFrequency.DAILY)),
                )

            val plans = ReminderScheduler.planAll(tasks, SchedulingCapabilities(), now)

            assertEquals(listOf("a", "c"), plans.map { it.taskId })
        }

        @Test
        fun `every reminder on a task is planned independently`() {
            val task =
                task().copy(
                    reminders =
                        listOf(
                            Reminder(
                                id = "reminder-lead",
                                taskId = "task-1",
                                trigger = ReminderTrigger.BeforeDue(10.minutes),
                            ),
                            Reminder(
                                id = "reminder-alarm",
                                taskId = "task-1",
                                trigger =
                                    ReminderTrigger.AtInstant(Instant.parse("2026-03-02T07:00:00Z"), london),
                                precision = ReminderPrecision.ALARM,
                            ),
                        ),
                )

            val plans = ReminderScheduler.plan(task, SchedulingCapabilities(), now)

            assertEquals(listOf("reminder-lead", "reminder-alarm"), plans.map { it.reminderId })
            assertEquals(
                listOf("2026-03-02T07:50", "2026-03-02T07:00"),
                plans.map { it.triggerAt.toLocalDateTime(london).toString() },
            )
            assertEquals(listOf(ReminderDelivery.INEXACT, ReminderDelivery.ALARM_CLOCK), plans.map { it.delivery })
        }
    }

    private fun plan(
        precision: ReminderPrecision,
        capabilities: SchedulingCapabilities = SchedulingCapabilities(),
        leadTime: kotlin.time.Duration = kotlin.time.Duration.ZERO,
        recurrence: RecurrenceRule? = null,
        now: Instant = this.now,
    ): ReminderPlan =
        requireNotNull(
            ReminderScheduler
                .plan(
                    task = task(precision = precision, leadTime = leadTime, recurrence = recurrence),
                    capabilities = capabilities,
                    now = now,
                ).singleOrNull(),
        ) { "expected a plan for $precision" }

    private fun task(
        id: String = "task-1",
        precision: ReminderPrecision = ReminderPrecision.GENTLE,
        leadTime: kotlin.time.Duration = kotlin.time.Duration.ZERO,
        recurrence: RecurrenceRule? = null,
    ) = StudyTask(
        id = id,
        title = "Revise chapter 4",
        dueAt = LocalDateTime(2026, 3, 2, 8, 0),
        timeZone = london,
        recurrence = recurrence,
        reminders =
            listOf(
                Reminder(
                    id = "reminder-$id",
                    taskId = id,
                    trigger = ReminderTrigger.BeforeDue(leadTime),
                    precision = precision,
                ),
            ),
        updatedAt = Instant.parse("2026-03-01T00:00:00Z"),
    )
}
