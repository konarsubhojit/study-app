package dev.studyflow.core.database.repository

import androidx.room.Room
import dev.studyflow.core.database.DATABASE_ROBOLECTRIC_SDK
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.ReminderTrigger
import dev.studyflow.core.model.SnoozeState
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subtask
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK
import dev.studyflow.core.testing.data.testReminder
import dev.studyflow.core.testing.data.testStudyTask
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [DATABASE_ROBOLECTRIC_SDK])
class OfflineFirstTaskRepositoryTest {
    private lateinit var database: StudyFlowDatabase
    private lateinit var repository: OfflineFirstTaskRepository

    // Late enough in the Berlin day that "today" and "tomorrow" differ from the UTC answer.
    private val now: Instant = Instant.parse("2026-03-01T23:30:00Z")
    private val zone = TimeZone.of("Europe/Berlin")

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StudyFlowDatabase::class.java)
                .build()
        repository = OfflineFirstTaskRepository(database.studyTaskDao(), { now }, { zone })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `day windows follow the user's zone, not UTC`() =
        runBlocking {
            // 2026-03-01T23:30Z is already 2026-03-02T00:30 in Berlin.
            listOf(
                task("overdue", LocalDateTime(2026, 3, 1, 20, 0)),
                task("today", LocalDateTime(2026, 3, 2, 18, 0)),
                task("upcoming", LocalDateTime(2026, 3, 3, 9, 0)),
            ).forEach { repository.save(it) }

            assertEquals(listOf("overdue"), repository.observeOverdue().first().map(StudyTask::id))
            assertEquals(listOf("today"), repository.observeToday().first().map(StudyTask::id))
            assertEquals(listOf("upcoming"), repository.observeUpcoming().first().map(StudyTask::id))
        }

    @Test
    fun `completed and deleted tasks drop out of the list screens`() =
        runBlocking {
            val due = LocalDateTime(2026, 3, 2, 18, 0)
            repository.save(task("open", due))
            repository.save(task("done", due).copy(completedAt = now))
            repository.save(task("tombstone", due))
            repository.delete("tombstone", deletedAt = now)

            assertEquals(listOf("open"), repository.observeToday().first().map(StudyTask::id))
            assertEquals(listOf("open", "done"), repository.observeTasks().first().map(StudyTask::id))
            assertNull(repository.observeTask("tombstone").first())
        }

    @Test
    fun `an aggregate round-trips with its tags, checklist and every reminder`() =
        runBlocking {
            val task =
                task("task-1", LocalDateTime(2026, 3, 2, 18, 0)).copy(
                    tags = setOf("exam", "revision"),
                    subtasks = listOf(Subtask(id = "sub-1", title = "Read chapter 4")),
                    reminders =
                        listOf(
                            testReminder(id = "r-1-lead", trigger = ReminderTrigger.BeforeDue(10.minutes)),
                            testReminder(
                                id = "r-2-exact",
                                trigger = ReminderTrigger.AtInstant(Instant.parse("2026-03-02T06:00:00Z"), zone),
                            ),
                        ),
                )
            repository.save(task)

            assertEquals(task, repository.observeTask("task-1").first())
            assertEquals(listOf("task-1"), repository.observeByTag("exam").first().map(StudyTask::id))
        }

    @Test
    fun `a split series is written as one unit`() =
        runBlocking {
            val due = LocalDateTime(2026, 3, 2, 18, 0)

            repository.saveAll(listOf(task("detached", due), task("series", due)))

            assertEquals(listOf("detached", "series"), repository.observeToday().first().map(StudyTask::id))
        }

    @Test
    fun `reminder state updates without rewriting the task`() =
        runBlocking {
            val reminder = testReminder(id = "r-1", trigger = ReminderTrigger.BeforeDue(10.minutes))
            repository.save(task("task-1", LocalDateTime(2026, 3, 2, 18, 0)).copy(reminders = listOf(reminder)))

            val fired =
                reminder.copy(
                    snooze = SnoozeState(until = now + 5.minutes, count = 1),
                    lastFiredAt = now,
                    schedulingId = "alarm-7",
                )
            repository.updateReminder(fired)

            assertEquals(listOf(fired), repository.observeTask("task-1").first()?.reminders)
        }

    @Test
    fun `due reminders resolve lead times against the task's due instant`() =
        runBlocking {
            repository.save(
                task("task-1", LocalDateTime(2026, 3, 2, 0, 20)).copy(
                    reminders = listOf(testReminder(id = "r-1", trigger = ReminderTrigger.BeforeDue(30.minutes))),
                ),
            )

            // Due 2026-03-01T23:20Z, so the thirty-minutes-before trigger fell at 22:50Z.
            assertEquals(listOf("r-1"), repository.remindersDueBy(now).map(Reminder::id))
            assertEquals(emptyList<String>(), repository.remindersDueBy(now - 60.minutes).map(Reminder::id))
        }

    private fun task(
        id: String,
        dueAt: LocalDateTime,
    ): StudyTask =
        testStudyTask(
            id = id,
            title = "Task $id",
            dueAt = dueAt,
            timeZone = zone,
            updatedAt = TEST_WALL_CLOCK,
        )
}
