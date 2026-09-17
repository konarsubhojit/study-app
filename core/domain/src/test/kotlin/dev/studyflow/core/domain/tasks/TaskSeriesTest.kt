package dev.studyflow.core.domain.tasks

import dev.studyflow.core.model.RecurrenceEnd
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.RecurrenceRule
import dev.studyflow.core.model.ReminderTrigger
import dev.studyflow.core.model.SnoozeState
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subtask
import dev.studyflow.core.testing.data.testReminder
import dev.studyflow.core.testing.data.testStudyTask
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@DisplayName("TaskSeries")
class TaskSeriesTest {
    private val london = TimeZone.of("Europe/London")
    private val eightAm = LocalDateTime(2026, 3, 2, 8, 0)
    private val now = Instant.parse("2026-03-02T09:00:00Z")

    @Nested
    @DisplayName("advancing")
    inner class Advancing {
        @Test
        fun `a completed occurrence moves the series to the next one`() {
            val advanced = TaskSeries.advance(task(RecurrenceRule(RecurrenceFrequency.DAILY)), now)

            assertEquals(LocalDateTime(2026, 3, 3, 8, 0), advanced.dueAt)
            assertNull(advanced.completedAt, "the series is still open; only the occurrence is done")
            assertEquals(now, advanced.updatedAt)
        }

        @Test
        fun `completing late consumes one occurrence, not every missed one`() {
            val task = task(RecurrenceRule(RecurrenceFrequency.DAILY))

            val advanced = TaskSeries.advance(task, Instant.parse("2026-03-05T09:00:00Z"))

            assertEquals(
                LocalDateTime(2026, 3, 3, 8, 0),
                advanced.dueAt,
                "jumping to today would drop the occurrences the user never saw",
            )
        }

        @Test
        fun `the final occurrence closes the task`() {
            val task = task(RecurrenceRule(RecurrenceFrequency.DAILY, end = RecurrenceEnd.AfterOccurrences(1)))

            val advanced = TaskSeries.advance(task, now)

            assertEquals(now, advanced.completedAt)
            assertEquals(eightAm, advanced.dueAt)
        }

        @Test
        fun `a one-off task is simply completed`() {
            val advanced = TaskSeries.advance(task(recurrence = null), now)

            assertEquals(now, advanced.completedAt)
        }

        @Test
        fun `the same occurrence cannot be consumed twice`() {
            val rule = RecurrenceRule(RecurrenceFrequency.DAILY, end = RecurrenceEnd.AfterOccurrences(4))

            val heads =
                generateSequence(task(rule)) { previous ->
                    TaskSeries.advance(previous, now).takeIf { !it.isCompleted }
                }

            assertEquals(
                listOf(
                    LocalDateTime(2026, 3, 2, 8, 0),
                    LocalDateTime(2026, 3, 3, 8, 0),
                    LocalDateTime(2026, 3, 4, 8, 0),
                    LocalDateTime(2026, 3, 5, 8, 0),
                ),
                heads.map { it.dueAt }.toList(),
            )
        }

        @Test
        fun `a snooze does not follow the series into the next occurrence`() {
            val task =
                task(RecurrenceRule(RecurrenceFrequency.DAILY)).copy(
                    reminders =
                        listOf(
                            testReminder(
                                snooze = SnoozeState(until = Instant.parse("2026-03-02T08:10:00Z")),
                                lastFiredAt = Instant.parse("2026-03-02T08:00:00Z"),
                            ),
                        ),
                )

            val advanced = TaskSeries.advance(task, now)

            assertNull(advanced.reminders.single().snooze)
            assertNull(advanced.reminders.single().lastFiredAt, "the next occurrence has not fired yet")
        }

        @Test
        fun `an absolute reminder keeps the record that it already fired`() {
            val firedAt = Instant.parse("2026-03-02T07:00:00Z")
            val task =
                task(RecurrenceRule(RecurrenceFrequency.DAILY)).copy(
                    reminders =
                        listOf(
                            testReminder(
                                trigger = ReminderTrigger.AtInstant(firedAt, london),
                                lastFiredAt = firedAt,
                            ),
                        ),
                )

            val advanced = TaskSeries.advance(task, now)

            assertEquals(firedAt, advanced.reminders.single().lastFiredAt)
        }
    }

    @Nested
    @DisplayName("editing this and future occurrences")
    inner class ThisAndFuture {
        @Test
        fun `edits the series in place`() {
            val task = task(RecurrenceRule(RecurrenceFrequency.DAILY))

            val edit =
                TaskSeries.edit(task, RecurrenceEditScope.THIS_AND_FUTURE, now, ::unusedId) {
                    it.copy(title = "Revise vectors")
                }

            assertNull(edit.series, "there is no past half of a series to preserve")
            assertEquals(task.id, edit.occurrence.id)
            assertEquals("Revise vectors", edit.occurrence.title)
            assertEquals(RecurrenceRule(RecurrenceFrequency.DAILY), edit.occurrence.recurrence)
            assertEquals(now, edit.occurrence.updatedAt)
        }
    }

    @Nested
    @DisplayName("editing this occurrence only")
    inner class ThisOccurrence {
        private val task =
            task(RecurrenceRule(RecurrenceFrequency.DAILY)).copy(
                subtasks = listOf(Subtask(id = "subtask-1", title = "Read chapter 4")),
                reminders = listOf(testReminder(schedulingId = "alarm-7")),
            )

        private val edit =
            TaskSeries.edit(task, RecurrenceEditScope.THIS_OCCURRENCE, now, ids()) {
                it.copy(dueAt = LocalDateTime(2026, 3, 2, 19, 0))
            }

        @Test
        fun `detaches the occurrence as a task of its own`() {
            assertEquals(LocalDateTime(2026, 3, 2, 19, 0), edit.occurrence.dueAt)
            assertNull(edit.occurrence.recurrence, "a moved occurrence must not repeat on its own")
            assertNotEquals(task.id, edit.occurrence.id)
        }

        @Test
        fun `the series carries on without the detached occurrence`() {
            val series = requireNotNull(edit.series)

            assertEquals(task.id, series.id, "the series keeps the identity the reminders point at")
            assertEquals(LocalDateTime(2026, 3, 3, 8, 0), series.dueAt)
        }

        @Test
        fun `every copied row gets an identity of its own`() {
            val detached = edit.occurrence

            assertNotEquals(task.subtasks.single().id, detached.subtasks.single().id)
            assertNotEquals(task.reminders.single().id, detached.reminders.single().id)
            assertEquals(detached.id, detached.reminders.single().taskId)
            assertNull(detached.reminders.single().schedulingId, "the copy owns no alarm registration yet")
        }

        @Test
        fun `both halves are offered for saving`() {
            assertEquals(2, edit.tasks.size)
        }

        @Test
        fun `detaching the last occurrence leaves no series behind`() {
            val last = task(RecurrenceRule(RecurrenceFrequency.DAILY, end = RecurrenceEnd.AfterOccurrences(1)))

            val edit = TaskSeries.edit(last, RecurrenceEditScope.THIS_OCCURRENCE, now, ids()) { it }

            assertNull(edit.series)
            assertEquals(listOf(edit.occurrence), edit.tasks)
        }

        @Test
        fun `a one-off task is edited in place whatever the scope`() {
            val edit =
                TaskSeries.edit(task(recurrence = null), RecurrenceEditScope.THIS_OCCURRENCE, now, ::unusedId) {
                    it.copy(title = "Hand in essay")
                }

            assertEquals("task-1", edit.occurrence.id)
            assertNull(edit.series)
        }
    }

    @Nested
    @DisplayName("removed occurrences")
    inner class RemovedOccurrences {
        @Test
        fun `an excluded date is never materialised as the head of the series`() {
            val rule =
                RecurrenceRule(
                    frequency = RecurrenceFrequency.DAILY,
                    exceptions = setOf(LocalDate(2026, 3, 3)),
                )

            val advanced = TaskSeries.advance(task(rule), now)

            assertEquals(LocalDateTime(2026, 3, 4, 8, 0), advanced.dueAt)
            assertTrue(
                advanced.recurrence
                    ?.exceptions
                    .orEmpty()
                    .isEmpty(),
                "a passed exception is dead weight",
            )
        }
    }

    private fun task(recurrence: RecurrenceRule?): StudyTask =
        testStudyTask(dueAt = eightAm, timeZone = london, recurrence = recurrence)

    private fun ids(): () -> String {
        var next = 0
        return { "copy-${next++}" }
    }

    private fun unusedId(): String = error("a scoped edit that touches the series needs no new ids")
}
