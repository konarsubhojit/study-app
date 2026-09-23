package dev.studyflow.core.domain.lifecycle

import dev.studyflow.core.model.RecurrenceEnd
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.RecurrenceRule
import dev.studyflow.core.model.ReminderPrecision
import dev.studyflow.core.model.ReminderTrigger
import dev.studyflow.core.model.SessionEventType
import dev.studyflow.core.model.SnoozeState
import dev.studyflow.core.model.Subtask
import dev.studyflow.core.model.TaskPriority
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK
import dev.studyflow.core.testing.data.testMaterial
import dev.studyflow.core.testing.data.testReminder
import dev.studyflow.core.testing.data.testSessionEvent
import dev.studyflow.core.testing.data.testStudySession
import dev.studyflow.core.testing.data.testStudyTask
import dev.studyflow.core.testing.data.testSubject
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.minutes

@DisplayName("Data archive format")
class DataArchiveCodecTest {
    @Test
    fun `a session and its event log survive the round trip unchanged`() {
        val session =
            SessionWithLog(
                session = testStudySession(note = "past papers").copy(manualOverride = true),
                events =
                    listOf(
                        testSessionEvent(id = "event-1", type = SessionEventType.STARTED, sequence = 0),
                        testSessionEvent(id = "event-2", type = SessionEventType.STOPPED, sequence = 1),
                    ),
            )

        val restored = DataArchiveCodec.decode(DataArchiveCodec.encode(archiveOf(sessions = listOf(session))))

        assertEquals(session, restored.sessions.single().toModel())
    }

    @Test
    fun `a task keeps its reminders, checklist and recurrence rule`() {
        val task =
            testStudyTask(
                dueAt = LocalDateTime(2026, 3, 2, 9, 0),
                timeZone = TimeZone.of("Europe/Berlin"),
                priority = TaskPriority.HIGH,
                tags = setOf("exam", "calculus"),
                subtasks = listOf(Subtask("subtask-1", "Chapter 4", completedAt = TEST_WALL_CLOCK)),
                recurrence =
                    RecurrenceRule(
                        frequency = RecurrenceFrequency.WEEKLY,
                        interval = 2,
                        daysOfWeek = setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY),
                        exceptions = setOf(LocalDate(2026, 4, 3)),
                        end = RecurrenceEnd.AfterOccurrences(6),
                    ),
                reminders =
                    listOf(
                        testReminder(id = "reminder-1", trigger = ReminderTrigger.BeforeDue(15.minutes)),
                        testReminder(
                            id = "reminder-2",
                            trigger = ReminderTrigger.AtInstant(TEST_WALL_CLOCK, TimeZone.of("Europe/Berlin")),
                            precision = ReminderPrecision.ALARM,
                            snooze = SnoozeState(TEST_WALL_CLOCK, count = 2),
                        ),
                    ),
            )

        val restored = DataArchiveCodec.decode(DataArchiveCodec.encode(archiveOf(tasks = listOf(task))))

        assertEquals(task, restored.tasks.single().toModel())
    }

    @Test
    fun `material metadata survives, and names the archive entry holding its bytes`() {
        val material = testMaterial(localUri = "/data/materials/material-1").copy(notes = "week 3")

        val archive = archiveOf(materials = listOf(material to "materials/material-1-notes.pdf"))
        val restored = DataArchiveCodec.decode(DataArchiveCodec.encode(archive)).materials.single()

        assertEquals("materials/material-1-notes.pdf", restored.archiveEntry)
        assertEquals(material.copy(localPath = "restored"), restored.toModel("restored"))
    }

    @Test
    fun `a subject survives the round trip`() {
        val subject = testSubject(archived = true)

        val restored = DataArchiveCodec.decode(DataArchiveCodec.encode(archiveOf(subjects = listOf(subject))))

        assertEquals(subject, restored.subjects.single().toModel())
    }

    @Test
    fun `an archive written by a newer app is refused rather than half-read`() {
        val document = DataArchiveCodec.encode(archiveOf().copy(schemaVersion = CURRENT_SCHEMA_VERSION + 1))

        val failure = assertThrows<ArchiveFormatException> { DataArchiveCodec.decode(document) }

        assertTrue(failure.message!!.contains("newer than this app understands"), failure.message)
    }

    @Test
    fun `a file that is not a StudyFlow archive is refused`() {
        assertThrows<ArchiveFormatException> { DataArchiveCodec.decode("this is not JSON at all") }
    }

    @Test
    fun `a hand-edited record that no model would accept names the record it came from`() {
        val document = DataArchiveCodec.encode(archiveOf(subjects = listOf(testSubject(id = "subject-9"))))
        val tampered = document.replace("\"name\": \"Mathematics\"", "\"name\": \"\"")

        val failure =
            assertThrows<ArchiveFormatException> {
                DataArchiveCodec
                    .decode(tampered)
                    .subjects
                    .single()
                    .toModel()
            }

        assertTrue(failure.message!!.contains("subject-9"), failure.message)
    }

    @Test
    fun `unknown fields from a future build of the same schema are ignored`() {
        val document =
            DataArchiveCodec
                .encode(archiveOf(subjects = listOf(testSubject())))
                .replace("\"subjects\": [", "\"studyStreaks\": [], \"subjects\": [")

        assertEquals(1, DataArchiveCodec.decode(document).subjects.size)
    }
}
