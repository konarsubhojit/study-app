package dev.studyflow.core.database.repository

import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.database.dao.StudyTaskDao
import dev.studyflow.core.database.entity.TaskWithReminders
import dev.studyflow.core.database.entity.asEntity
import dev.studyflow.core.database.entity.asExternalModel
import dev.studyflow.core.domain.tasks.TaskRepository
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.StudyTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The local database is the source of truth for tasks; nothing here waits on a network.
 *
 * Day boundaries are resolved per collection from the injected clock and time-zone provider, and
 * handed to SQL as instants. Doing that arithmetic in Kotlin is what keeps "today" meaning the
 * user's local day — SQLite has no time-zone database — while the query still filters on an indexed
 * UTC column.
 */
public class OfflineFirstTaskRepository(
    private val dao: StudyTaskDao,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
) : TaskRepository {
    override fun observeTasks(): Flow<List<StudyTask>> = dao.observeAll().asExternalModels()

    override fun observeOverdue(): Flow<List<StudyTask>> = window { now, _ -> dao.observeOverdue(now) }

    override fun observeToday(): Flow<List<StudyTask>> =
        window { now, zone -> dao.observeDueBetween(now.startOfDay(zone), now.startOfNextDay(zone)) }

    override fun observeUpcoming(): Flow<List<StudyTask>> =
        window { now, zone -> dao.observeDueFrom(now.startOfNextDay(zone)) }

    override fun observeBySubject(subjectId: String): Flow<List<StudyTask>> =
        dao.observeBySubject(subjectId).asExternalModels()

    override fun observeByTag(tag: String): Flow<List<StudyTask>> = dao.observeByTag(tag).asExternalModels()

    override fun observeTask(id: String): Flow<StudyTask?> =
        dao.observeById(id).map { row -> row?.takeUnless { it.task.deleted }?.asExternalModel() }

    override suspend fun save(task: StudyTask) {
        dao.save(task.asEntity())
    }

    override suspend fun saveAll(values: List<StudyTask>) {
        dao.saveAll(values.map { it.asEntity() })
    }

    override suspend fun delete(
        id: String,
        deletedAt: Instant,
    ) {
        dao.softDelete(id, deletedAt)
    }

    override suspend fun updateReminder(reminder: Reminder) {
        dao.updateReminderState(
            id = reminder.id,
            snoozeUntil = reminder.snooze?.until,
            snoozeCount = reminder.snooze?.count,
            lastFiredAt = reminder.lastFiredAt,
            schedulingId = reminder.schedulingId,
        )
    }

    override suspend fun remindersDueBy(now: Instant): List<Reminder> =
        dao.remindersDueBy(now).map { it.asExternalModel() }

    private fun window(query: (Instant, TimeZone) -> Flow<List<TaskWithReminders>>): Flow<List<StudyTask>> =
        flow { emitAll(query(clock.now(), timeZoneProvider.current())) }.asExternalModels()

    private fun Flow<List<TaskWithReminders>>.asExternalModels(): Flow<List<StudyTask>> =
        map { rows -> rows.map(TaskWithReminders::asExternalModel) }

    private fun Instant.startOfDay(zone: TimeZone): Instant = toLocalDateTime(zone).date.atStartOfDayIn(zone)

    private fun Instant.startOfNextDay(zone: TimeZone): Instant =
        toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
}
