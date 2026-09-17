package dev.studyflow.core.database

import androidx.room.withTransaction
import dev.studyflow.core.database.entity.FolderEntity
import dev.studyflow.core.database.entity.MaterialEntity
import dev.studyflow.core.database.entity.MaterialSyncState
import dev.studyflow.core.database.entity.RecurrenceEndType
import dev.studyflow.core.database.entity.ReminderEntity
import dev.studyflow.core.database.entity.ReminderTriggerType
import dev.studyflow.core.database.entity.SessionEventEntity
import dev.studyflow.core.database.entity.StudySessionEntity
import dev.studyflow.core.database.entity.StudyTaskEntity
import dev.studyflow.core.database.entity.SubjectEntity
import dev.studyflow.core.database.entity.SubtaskEntity
import dev.studyflow.core.database.entity.TaskTagEntity
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.ReminderPrecision
import dev.studyflow.core.model.SessionEventType
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.TaskPriority
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Counts are intentional profile parameters rather than business-logic constants. */
@Suppress("MagicNumber")
public enum class SyntheticDataProfile(
    internal val size: SyntheticDataSize,
) {
    DEVELOPMENT(SyntheticDataSize(6, 12, 120, 80, 40, 8)),
    PERFORMANCE(SyntheticDataSize(40, 200, 10_000, 5_000, 2_000, 20)),
}

public data class SyntheticDataSize(
    val subjectCount: Int,
    val folderCount: Int,
    val materialCount: Int,
    val taskCount: Int,
    val sessionCount: Int,
    val eventsPerSession: Int,
) {
    init {
        require(subjectCount > 0)
        require(folderCount >= 0)
        require(materialCount >= 0)
        require(taskCount >= 0)
        require(sessionCount >= 0)
        require(eventsPerSession >= 1)
    }
}

public data class SyntheticDataSet(
    val subjects: List<SubjectEntity>,
    val folders: List<FolderEntity>,
    val materials: List<MaterialEntity>,
    val tasks: List<StudyTaskEntity>,
    val reminders: List<ReminderEntity>,
    val taskTags: List<TaskTagEntity>,
    val subtasks: List<SubtaskEntity>,
    val sessions: List<StudySessionEntity>,
    val sessionEvents: List<SessionEventEntity>,
)

/** Deterministic fixtures large enough to exercise realistic query cardinalities. */
@Suppress("MagicNumber")
public object SyntheticDataFactory {
    public fun create(profile: SyntheticDataProfile): SyntheticDataSet = create(profile.size)

    public fun create(size: SyntheticDataSize): SyntheticDataSet {
        val subjects = createSubjects(size.subjectCount)
        val folders = createFolders(size.folderCount)
        val tasks = createTasks(size.taskCount, subjects)
        val sessions = createSessions(size.sessionCount, subjects, size.eventsPerSession)
        return SyntheticDataSet(
            subjects = subjects,
            folders = folders,
            materials = createMaterials(size.materialCount, folders, subjects),
            tasks = tasks,
            reminders = createReminders(tasks),
            taskTags = createTaskTags(tasks),
            subtasks = createSubtasks(tasks),
            sessions = sessions,
            sessionEvents = createSessionEvents(sessions, size.eventsPerSession),
        )
    }

    private fun createSubjects(count: Int): List<SubjectEntity> =
        List(count) { index ->
            SubjectEntity(
                id = "subject-$index",
                name = "Subject ${index.toString().padStart(3, '0')}",
                colorArgb = 0xff000000.toInt() or (index * 2_654_435),
                archived = index % 11 == 0,
            )
        }

    private fun createFolders(count: Int): List<FolderEntity> =
        List(count) { index ->
            FolderEntity(
                id = "folder-$index",
                parentId = if (index < ROOT_FOLDER_COUNT) null else "folder-${index % ROOT_FOLDER_COUNT}",
                name = "Folder ${index.toString().padStart(4, '0')}",
                createdAt = BASE_INSTANT + index.minutes,
            )
        }

    private fun createMaterials(
        count: Int,
        folders: List<FolderEntity>,
        subjects: List<SubjectEntity>,
    ): List<MaterialEntity> =
        List(count) { index ->
            val syncState = MaterialSyncState.entries[index % MaterialSyncState.entries.size]
            MaterialEntity(
                id = "material-$index",
                folderId = folders.getOrNull(index % folders.size.coerceAtLeast(1))?.id,
                subjectId = subjects.getOrNull(index % subjects.size.coerceAtLeast(1))?.id,
                displayName = "Lecture ${index.toString().padStart(6, '0')}.pdf",
                mimeType = "application/pdf",
                sizeBytes = 1_024L + index,
                contentHash = ContentHash(index.toString(16).padStart(HASH_LENGTH, '0')),
                createdAt = BASE_INSTANT + index.minutes,
                updatedAt = BASE_INSTANT + index.minutes,
                notes = if (index % 4 == 0) "Synthetic material notes" else null,
                remoteKey = "materials/${index.toString(16).padStart(HASH_LENGTH, '0')}",
                syncState = syncState,
                uploadedBytes = if (syncState == MaterialSyncState.UPLOADING) 512L else null,
                uploadTotalBytes = if (syncState == MaterialSyncState.UPLOADING) 1_024L + index else null,
                failureReason = if (syncState == MaterialSyncState.FAILED) "synthetic transient failure" else null,
                failureRetryable = if (syncState == MaterialSyncState.FAILED) true else null,
                localPath = "/studyflow/material-$index",
                pinnedForOffline = index % 10 == 0,
                encrypted = index % 7 == 0,
                deleted = index % DELETED_MATERIAL_MODULUS == 0,
                pageCount = null,
                duration = null,
            )
        }

    private fun createTasks(
        count: Int,
        subjects: List<SubjectEntity>,
    ): List<StudyTaskEntity> =
        List(count) { index ->
            val dueAtUtc = BASE_DUE_INSTANT + (index * 15).minutes
            StudyTaskEntity(
                id = "task-$index",
                title = "Task ${index.toString().padStart(5, '0')}",
                notes = if (index % 3 == 0) "Synthetic task notes" else null,
                subjectId = subjects[index % subjects.size].id,
                materialId = null,
                sessionId = null,
                dueAt = dueAtUtc.toLocalDateTime(TimeZone.UTC),
                dueAtUtc = dueAtUtc,
                timeZone = TimeZone.UTC,
                isAllDay = index % 9 == 0,
                priority = TaskPriority.entries[index % TaskPriority.entries.size],
                recurrenceFrequency = if (index % 2 == 0) RecurrenceFrequency.WEEKLY else null,
                recurrenceInterval = if (index % 2 == 0) 1 else null,
                recurrenceDaysOfWeek =
                    if (index % 2 == 0) setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY) else null,
                recurrenceDayOfMonth = null,
                recurrenceWeekOfMonth = null,
                recurrenceMonthOfYear = null,
                recurrenceExceptions = null,
                recurrenceEndType = if (index % 2 == 0) RecurrenceEndType.NEVER else null,
                recurrenceEndCount = null,
                recurrenceEndDate = null,
                completedAt = if (index % 5 == 0) BASE_INSTANT else null,
                updatedAt = BASE_INSTANT + index.minutes,
                deleted = index % 23 == 0,
            )
        }

    // Every second task carries a lead-time reminder and every seventh an extra alarm, so the
    // fixture exercises the many-reminders-per-task cardinality the queries have to survive.
    private fun createReminders(tasks: List<StudyTaskEntity>): List<ReminderEntity> =
        tasks.flatMapIndexed { index, task ->
            buildList {
                if (index % 2 == 0) {
                    add(
                        ReminderEntity(
                            id = "reminder-$index-lead",
                            taskId = task.id,
                            triggerType = ReminderTriggerType.BEFORE_DUE,
                            leadTime = 15.minutes,
                            triggerInstant = null,
                            triggerTimeZone = null,
                            triggerAtUtc = task.dueAtUtc?.minus(15.minutes),
                            precision = ReminderPrecision.GENTLE,
                            snoozeUntil = null,
                            snoozeCount = null,
                            lastFiredAt = null,
                            schedulingId = null,
                        ),
                    )
                }
                if (index % 7 == 0) {
                    val ringAt = requireNotNull(task.dueAtUtc) - 1.hours
                    add(
                        ReminderEntity(
                            id = "reminder-$index-alarm",
                            taskId = task.id,
                            triggerType = ReminderTriggerType.AT_INSTANT,
                            leadTime = null,
                            triggerInstant = ringAt,
                            triggerTimeZone = TimeZone.UTC,
                            triggerAtUtc = ringAt,
                            precision = ReminderPrecision.ALARM,
                            snoozeUntil = null,
                            snoozeCount = null,
                            lastFiredAt = null,
                            schedulingId = null,
                        ),
                    )
                }
            }
        }

    private fun createTaskTags(tasks: List<StudyTaskEntity>): List<TaskTagEntity> =
        tasks.flatMapIndexed { index, task ->
            TAGS
                .filterIndexed { tagIndex, _ -> (index + tagIndex) % TAGS.size == 0 }
                .map { TaskTagEntity(taskId = task.id, tag = it) }
        }

    private fun createSubtasks(tasks: List<StudyTaskEntity>): List<SubtaskEntity> =
        tasks.filterIndexed { index, _ -> index % 4 == 0 }.flatMap { task ->
            List(SUBTASKS_PER_TASK) { position ->
                SubtaskEntity(
                    id = "${task.id}-subtask-$position",
                    taskId = task.id,
                    position = position,
                    title = "Step ${position + 1}",
                    completedAt = if (position == 0) BASE_INSTANT else null,
                )
            }
        }

    // The projection mirrors what folding the generated event log produces, so synthetic data
    // exercises the same reads as real data instead of a shape that could never occur.
    private fun createSessions(
        count: Int,
        subjects: List<SubjectEntity>,
        eventsPerSession: Int,
    ): List<StudySessionEntity> =
        List(count) { index ->
            val startedAt = BASE_INSTANT + (index * eventsPerSession).minutes
            val endedAt = startedAt + (eventsPerSession - 1).minutes
            val status =
                when (eventType(eventsPerSession - 1, eventsPerSession)) {
                    SessionEventType.STOPPED -> SessionStatus.STOPPED
                    SessionEventType.STARTED, SessionEventType.RESUMED -> SessionStatus.RUNNING
                    SessionEventType.PAUSED -> SessionStatus.PAUSED
                }
            StudySessionEntity(
                id = "session-$index",
                subjectId = subjects[index % subjects.size].id,
                note = if (index % 4 == 0) "Synthetic session note" else null,
                status = status,
                startedAt = startedAt,
                endedAt = endedAt.takeIf { status == SessionStatus.STOPPED },
                deviceId = SYNTHETIC_DEVICE_ID,
                updatedAt = endedAt,
            )
        }

    private fun createSessionEvents(
        sessions: List<StudySessionEntity>,
        eventsPerSession: Int,
    ): List<SessionEventEntity> =
        sessions.flatMapIndexed { sessionIndex, session ->
            List(eventsPerSession) { sequence ->
                SessionEventEntity(
                    id = "${session.id}-event-$sequence",
                    sessionId = session.id,
                    type = eventType(sequence, eventsPerSession),
                    uptime = (sessionIndex * eventsPerSession + sequence).minutes,
                    wallClock = BASE_INSTANT + (sessionIndex * eventsPerSession + sequence).minutes,
                    bootId = SYNTHETIC_BOOT_ID,
                    sequence = sequence.toLong(),
                )
            }
        }

    private fun eventType(
        sequence: Int,
        eventsPerSession: Int,
    ): SessionEventType =
        when {
            sequence == 0 -> SessionEventType.STARTED
            sequence == eventsPerSession - 1 -> SessionEventType.STOPPED
            sequence % 2 == 1 -> SessionEventType.PAUSED
            else -> SessionEventType.RESUMED
        }

    private val TAGS: List<String> = listOf("exam", "homework", "revision")
    private const val SUBTASKS_PER_TASK: Int = 3
    private const val ROOT_FOLDER_COUNT: Int = 4
    private const val DELETED_MATERIAL_MODULUS: Int = 37
    private const val HASH_LENGTH: Int = 64
    private val BASE_INSTANT: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val BASE_DUE_INSTANT: Instant = Instant.parse("2026-01-01T09:00:00Z")
    private val SYNTHETIC_BOOT_ID: BootId = BootId("synthetic-boot")
    private const val SYNTHETIC_DEVICE_ID: String = "synthetic-device"
}

/** Inserts a dataset once, atomically. Existing user data is never mixed with generated rows. */
public object SyntheticDataSeeder {
    public suspend fun seedIfEmpty(
        database: StudyFlowDatabase,
        profile: SyntheticDataProfile,
    ): Boolean = seedIfEmpty(database, SyntheticDataFactory.create(profile))

    public suspend fun seedIfEmpty(
        database: StudyFlowDatabase,
        data: SyntheticDataSet,
    ): Boolean =
        database.withTransaction {
            if (!database.isEmpty()) {
                false
            } else {
                database.subjectDao().upsertAll(data.subjects)
                database.folderDao().upsertAll(data.folders)
                database.materialDao().upsertAll(data.materials)
                database.studyTaskDao().upsertTasks(data.tasks)
                database.studyTaskDao().insertReminders(data.reminders)
                database.studyTaskDao().insertTags(data.taskTags)
                database.studyTaskDao().insertSubtasks(data.subtasks)
                database.sessionDao().upsertSessions(data.sessions)
                database.sessionDao().insertEvents(data.sessionEvents)
                true
            }
        }

    private suspend fun StudyFlowDatabase.isEmpty(): Boolean =
        subjectDao().count() == 0 &&
            folderDao().count() == 0 &&
            materialDao().count() == 0 &&
            studyTaskDao().count() == 0 &&
            sessionDao().count() == 0
}
