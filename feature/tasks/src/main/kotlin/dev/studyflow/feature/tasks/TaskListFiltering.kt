package dev.studyflow.feature.tasks

import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.TaskPriority

/** How the tasks within a section are ordered, chosen once for the whole list (issue #46). */
public enum class TaskSort {
    /** Soonest due first; undated tasks last. */
    DUE_DATE,

    /** Most important first; ties broken by due date. */
    PRIORITY,
}

/**
 * The narrowing the user has applied on top of the section a task already belongs to.
 *
 * Kept as one small value type instead of scattered `ViewModel` fields so [applyTo] can be unit
 * tested without a `ViewModel`, a `Flow`, or a repository in sight.
 */
public data class TaskListFilter(
    val query: String = "",
    val subjectId: String? = null,
    val tag: String? = null,
    val priority: TaskPriority? = null,
    val sort: TaskSort = TaskSort.DUE_DATE,
) {
    /** True once the user has narrowed the list beyond its default sort. */
    public val isNarrowed: Boolean
        get() = query.isNotBlank() || subjectId != null || tag != null || priority != null
}

/**
 * Applies search, subject/tag/priority filters and sort order to an already-windowed list.
 *
 * The windowing itself — overdue, today, upcoming — is
 * [dev.studyflow.core.domain.tasks.TaskRepository]'s job: this function only ever narrows and
 * reorders a list it is handed, never re-derives a day boundary from the clock.
 */
public fun List<StudyTask>.applyTo(filter: TaskListFilter): List<StudyTask> =
    asSequence()
        .filter { matchesQuery(it, filter.query) }
        .filter { filter.subjectId == null || it.subjectId == filter.subjectId }
        .filter { filter.tag == null || filter.tag in it.tags }
        .filter { filter.priority == null || it.priority == filter.priority }
        .sortedWith(filter.sort.comparator())
        .toList()

private fun matchesQuery(
    task: StudyTask,
    query: String,
): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim()
    return task.title.contains(needle, ignoreCase = true) ||
        task.notes?.contains(needle, ignoreCase = true) == true ||
        task.tags.any { it.contains(needle, ignoreCase = true) }
}

private fun TaskSort.comparator(): Comparator<StudyTask> =
    when (this) {
        TaskSort.DUE_DATE -> {
            compareBy({ it.dueAtUtc == null }, { it.dueAtUtc })
        }

        TaskSort.PRIORITY -> {
            compareByDescending<StudyTask> { it.priority.ordinal }
                .thenBy { it.dueAtUtc == null }
                .thenBy { it.dueAtUtc }
        }
    }

/**
 * Every open, undated task — the one section [dev.studyflow.core.domain.tasks.TaskRepository] has
 * no dedicated query for.
 */
public fun List<StudyTask>.someday(): List<StudyTask> = filter { !it.isCompleted && it.dueAt == null }

/** The subjects and tags visible across the user's open tasks, used to populate filter chips. */
public data class TaskFilterOptions(
    val subjectIds: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
)

public fun List<StudyTask>.filterOptions(): TaskFilterOptions =
    TaskFilterOptions(
        subjectIds = filterNot(StudyTask::isCompleted).mapNotNull(StudyTask::subjectId).toSet(),
        tags = filterNot(StudyTask::isCompleted).flatMap(StudyTask::tags).toSet(),
    )
