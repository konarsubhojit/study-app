package dev.studyflow.feature.tasks

import dev.studyflow.core.model.TaskPriority
import dev.studyflow.core.testing.data.testStudyTask
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TaskListFiltering")
class TaskListFilteringTest {
    private val dueSoon =
        testStudyTask(id = "task-due-soon", title = "Revise calculus", dueAt = LocalDateTime(2026, 3, 2, 9, 0))
    private val dueLater =
        testStudyTask(id = "task-due-later", title = "Read chapter 4", dueAt = LocalDateTime(2026, 3, 5, 9, 0))
    private val highPriorityLater =
        testStudyTask(
            id = "task-high-priority",
            title = "Write essay outline",
            dueAt = LocalDateTime(2026, 3, 9, 9, 0),
            priority = TaskPriority.HIGH,
            tags = setOf("writing"),
            subjectId = "subject-english",
        )
    private val tasks = listOf(dueSoon, dueLater, highPriorityLater)

    @Test
    fun `an unnarrowed filter sorts by due date without dropping anything`() {
        val result = tasks.applyTo(TaskListFilter())

        assertEquals(listOf(dueSoon.id, dueLater.id, highPriorityLater.id), result.map { it.id })
    }

    @Test
    fun `sorting by priority puts the highest priority first regardless of due date`() {
        val result = tasks.applyTo(TaskListFilter(sort = TaskSort.PRIORITY))

        assertEquals(highPriorityLater.id, result.first().id)
    }

    @Test
    fun `a search query matches the title, the notes or a tag, case-insensitively`() {
        assertEquals(listOf(dueSoon.id), tasks.applyTo(TaskListFilter(query = "CALCULUS")).map { it.id })
        assertEquals(listOf(highPriorityLater.id), tasks.applyTo(TaskListFilter(query = "writing")).map { it.id })
    }

    @Test
    fun `a subject filter narrows to tasks with that subject`() {
        val result = tasks.applyTo(TaskListFilter(subjectId = "subject-english"))

        assertEquals(listOf(highPriorityLater.id), result.map { it.id })
    }

    @Test
    fun `a tag filter narrows to tasks carrying that tag`() {
        val result = tasks.applyTo(TaskListFilter(tag = "writing"))

        assertEquals(listOf(highPriorityLater.id), result.map { it.id })
    }

    @Test
    fun `a priority filter narrows to tasks at exactly that priority`() {
        val result = tasks.applyTo(TaskListFilter(priority = TaskPriority.HIGH))

        assertEquals(listOf(highPriorityLater.id), result.map { it.id })
    }

    @Test
    fun `an unfiltered state is reported as not narrowed`() {
        assertTrue(TaskListFilter().isNarrowed.not())
        assertTrue(TaskListFilter(query = "x").isNarrowed)
        assertTrue(TaskListFilter(subjectId = "s").isNarrowed)
        assertTrue(TaskListFilter(priority = TaskPriority.HIGH).isNarrowed)
    }

    @Test
    fun `someday is every open task without a due date, and only those`() {
        val undated = testStudyTask(id = "task-undated", title = "Someday task", dueAt = null)
        val completedUndated =
            testStudyTask(
                id = "task-completed-undated",
                title = "Done already",
                dueAt = null,
                completedAt = dueSoon.updatedAt,
            )

        val result = listOf(dueSoon, undated, completedUndated).someday()

        assertEquals(listOf(undated.id), result.map { it.id })
    }

    @Test
    fun `filter options collect the distinct subjects and tags of open tasks only`() {
        val completedWithSubject =
            testStudyTask(id = "task-completed", subjectId = "subject-done", completedAt = dueSoon.updatedAt)

        val options = (tasks + completedWithSubject).filterOptions()

        assertEquals(setOf("subject-english"), options.subjectIds)
        assertEquals(setOf("writing"), options.tags)
    }
}
