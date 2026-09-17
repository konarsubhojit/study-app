package dev.studyflow.core.testing.data

import dev.studyflow.core.domain.tasks.TaskRepository
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.ReminderTrigger
import dev.studyflow.core.model.StudyTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * In-memory [TaskRepository] with the same visible behaviour as the database implementation:
 * tombstones stay out of every list, and the day windows come from [now] and [timeZone].
 *
 * A screen test that asserts "this shows what is due today" should not have to stand up Room, but
 * it must not pass against a fake that is more forgiving than production either — hence the same
 * filtering and ordering rules rather than a bare list.
 */
public class FakeTaskRepository(
    public var now: Instant = TEST_WALL_CLOCK,
    public var timeZone: TimeZone = TimeZone.UTC,
) : TaskRepository {
    private val tasks = MutableStateFlow<List<StudyTask>>(emptyList())

    override fun observeTasks(): Flow<List<StudyTask>> =
        tasks.map { list ->
            list
                .filterNot(StudyTask::deleted)
                .sortedWith(compareBy({ it.isCompleted }, { it.dueAtUtc == null }, { it.dueAtUtc }, { it.id }))
        }

    override fun observeOverdue(): Flow<List<StudyTask>> = visible { it.isOverdueAt(now) }

    override fun observeToday(): Flow<List<StudyTask>> =
        visible { task -> task.isOpenAndDue { it >= startOfDay() && it < startOfNextDay() } }

    override fun observeUpcoming(): Flow<List<StudyTask>> =
        visible { task -> task.isOpenAndDue { it >= startOfNextDay() } }

    override fun observeBySubject(subjectId: String): Flow<List<StudyTask>> = visible { it.subjectId == subjectId }

    override fun observeByTag(tag: String): Flow<List<StudyTask>> = visible { tag in it.tags }

    override fun observeTask(id: String): Flow<StudyTask?> =
        tasks.map { list -> list.firstOrNull { it.id == id && !it.deleted } }

    override suspend fun save(task: StudyTask) {
        saveAll(listOf(task))
    }

    override suspend fun saveAll(tasks: List<StudyTask>) {
        val ids = tasks.mapTo(mutableSetOf(), StudyTask::id)
        this.tasks.value = this.tasks.value.filterNot { it.id in ids } + tasks
    }

    override suspend fun delete(
        id: String,
        deletedAt: Instant,
    ) {
        tasks.value = tasks.value.map { if (it.id == id) it.copy(deleted = true, updatedAt = deletedAt) else it }
    }

    override suspend fun updateReminder(reminder: Reminder) {
        tasks.value =
            tasks.value.map { task ->
                if (task.reminders.none { it.id == reminder.id }) {
                    task
                } else {
                    task.copy(reminders = task.reminders.map { if (it.id == reminder.id) reminder else it })
                }
            }
    }

    override suspend fun remindersDueBy(now: Instant): List<Reminder> =
        tasks.value
            .filterNot(StudyTask::deleted)
            .flatMap { task -> task.reminders.filter { it.triggerAtUtc(task)?.let { at -> at <= now } == true } }

    private fun visible(predicate: (StudyTask) -> Boolean): Flow<List<StudyTask>> =
        tasks.map { list ->
            list
                .filterNot(StudyTask::deleted)
                .filter(predicate)
                .sortedWith(compareBy({ it.dueAtUtc }, { it.id }))
        }

    private fun StudyTask.isOpenAndDue(window: (Instant) -> Boolean): Boolean =
        !isCompleted && (dueAtUtc?.let(window) == true)

    private fun Reminder.triggerAtUtc(task: StudyTask): Instant? =
        when (val trigger = trigger) {
            is ReminderTrigger.BeforeDue -> task.dueAtUtc?.minus(trigger.leadTime)
            is ReminderTrigger.AtInstant -> trigger.instant
        }

    private fun startOfDay(): Instant = now.toLocalDateTime(timeZone).date.atStartOfDayIn(timeZone)

    private fun startOfNextDay(): Instant =
        now
            .toLocalDateTime(timeZone)
            .date
            .plus(1, DateTimeUnit.DAY)
            .atStartOfDayIn(timeZone)
}
