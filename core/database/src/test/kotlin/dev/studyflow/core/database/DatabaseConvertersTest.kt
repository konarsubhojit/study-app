package dev.studyflow.core.database

import dev.studyflow.core.database.entity.asEntity
import dev.studyflow.core.database.entity.asExternalModel
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.RecurrenceEnd
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.RecurrenceRule
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.ReminderPrecision
import dev.studyflow.core.model.ReminderTrigger
import dev.studyflow.core.model.SnoozeState
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subtask
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.model.TaskPriority
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DatabaseConvertersTest {
    private val converters = DatabaseConverters()

    @Test
    fun `instant converter stores UTC epoch milliseconds`() {
        val instant = Instant.parse("2026-09-16T23:24:29.317Z")

        val stored = converters.instantToEpochMillis(instant)

        assertEquals(1_789_601_069_317L, stored)
        assertEquals(instant, converters.epochMillisToInstant(stored))
    }

    @Test
    fun `material mapping preserves every sync variant and encryption`() {
        val states =
            listOf(
                SyncState.Pending,
                SyncState.Uploading(uploadedBytes = 512, totalBytes = 1_024),
                SyncState.Synced,
                SyncState.Failed(reason = "offline", retryable = true),
            )

        states.forEachIndexed { index, state ->
            val material =
                Material(
                    id = "material-$index",
                    displayName = "notes.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 1_024,
                    contentHash = ContentHash(index.toString(16).padStart(64, '0')),
                    createdAt = Instant.parse("2026-09-16T23:24:29.317Z"),
                    sync = state,
                    localUri = "content://notes",
                    pinnedForOffline = true,
                    encrypted = true,
                )

            assertEquals(material, material.asEntity().asExternalModel())
        }
    }

    @Test
    fun `task mapping preserves local time zone, recurrence and every reminder`() {
        val task =
            StudyTask(
                id = "task-1",
                title = "Revise databases",
                dueAt = LocalDateTime.parse("2026-10-25T09:00"),
                timeZone = TimeZone.of("Europe/London"),
                priority = TaskPriority.HIGH,
                tags = setOf("exam", "revision"),
                subtasks =
                    listOf(
                        Subtask(id = "subtask-1", title = "Read chapter 4"),
                        Subtask(
                            id = "subtask-2",
                            title = "Past paper",
                            completedAt = Instant.parse("2026-10-24T18:00:00Z"),
                        ),
                    ),
                recurrence =
                    RecurrenceRule(
                        frequency = RecurrenceFrequency.WEEKLY,
                        interval = 2,
                        daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
                        end = RecurrenceEnd.OnDate(LocalDate.parse("2027-01-31")),
                    ),
                reminders =
                    listOf(
                        Reminder(
                            id = "reminder-1",
                            taskId = "task-1",
                            trigger = ReminderTrigger.BeforeDue(30.minutes),
                            precision = ReminderPrecision.EXACT,
                            lastFiredAt = Instant.parse("2026-10-18T08:30:00Z"),
                            schedulingId = "alarm-17",
                        ),
                        Reminder(
                            id = "reminder-2",
                            taskId = "task-1",
                            trigger =
                                ReminderTrigger.AtInstant(
                                    instant = Instant.parse("2026-10-25T06:00:00Z"),
                                    timeZone = TimeZone.of("Europe/London"),
                                ),
                            precision = ReminderPrecision.ALARM,
                            snooze = SnoozeState(until = Instant.parse("2026-10-25T06:10:00Z"), count = 2),
                        ),
                    ),
                updatedAt = Instant.parse("2026-10-01T12:00:00Z"),
            )

        assertEquals(task, task.asEntity().asExternalModel())
    }

    @Test
    fun `task mapping derives the due and trigger instants the list queries are indexed on`() {
        val task =
            StudyTask(
                id = "task-1",
                title = "Revise databases",
                // British Summer Time: 09:00 local is 08:00 UTC, which a naive copy would get wrong.
                dueAt = LocalDateTime.parse("2026-10-24T09:00"),
                timeZone = TimeZone.of("Europe/London"),
                reminders =
                    listOf(
                        Reminder(
                            id = "reminder-1",
                            taskId = "task-1",
                            trigger = ReminderTrigger.BeforeDue(10.minutes),
                        ),
                    ),
                updatedAt = Instant.parse("2026-10-01T12:00:00Z"),
            )

        val entity = task.asEntity()

        assertEquals(Instant.parse("2026-10-24T08:00:00Z"), entity.task.dueAtUtc)
        assertEquals(Instant.parse("2026-10-24T07:50:00Z"), entity.reminders.single().triggerAtUtc)
    }
}
