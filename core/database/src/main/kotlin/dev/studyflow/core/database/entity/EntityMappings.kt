package dev.studyflow.core.database.entity

import dev.studyflow.core.domain.session.SessionDescriptor
import dev.studyflow.core.domain.session.SessionReducer
import dev.studyflow.core.model.Folder
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.RecurrenceEnd
import dev.studyflow.core.model.RecurrenceRule
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subject
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.model.TimeAnchor

public fun Subject.asEntity(): SubjectEntity = SubjectEntity(id, name, colorArgb, archived)

public fun SubjectEntity.asExternalModel(): Subject = Subject(id, name, colorArgb, archived)

public fun Folder.asEntity(): FolderEntity = FolderEntity(id, parentId, name, createdAt)

public fun FolderEntity.asExternalModel(): Folder = Folder(id, parentId, name, createdAt)

public fun Material.asEntity(): MaterialEntity {
    val upload = sync as? SyncState.Uploading
    val failure = sync as? SyncState.Failed
    return MaterialEntity(
        id = id,
        folderId = folderId,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        contentHash = contentHash,
        createdAt = createdAt,
        syncState = sync.asEntity(),
        uploadedBytes = upload?.uploadedBytes,
        uploadTotalBytes = upload?.totalBytes,
        failureReason = failure?.reason,
        failureRetryable = failure?.retryable,
        localUri = localUri,
        pinnedForOffline = pinnedForOffline,
        encrypted = encrypted,
    )
}

public fun MaterialEntity.asExternalModel(): Material =
    Material(
        id = id,
        folderId = folderId,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        contentHash = contentHash,
        createdAt = createdAt,
        sync = asExternalSyncState(),
        localUri = localUri,
        pinnedForOffline = pinnedForOffline,
        encrypted = encrypted,
    )

private fun SyncState.asEntity(): MaterialSyncState =
    when (this) {
        SyncState.Pending -> MaterialSyncState.PENDING
        is SyncState.Uploading -> MaterialSyncState.UPLOADING
        SyncState.Synced -> MaterialSyncState.SYNCED
        is SyncState.Failed -> MaterialSyncState.FAILED
    }

private fun MaterialEntity.asExternalSyncState(): SyncState =
    when (syncState) {
        MaterialSyncState.PENDING -> {
            SyncState.Pending
        }

        MaterialSyncState.UPLOADING -> {
            SyncState.Uploading(
                uploadedBytes = requireNotNull(uploadedBytes) { "UPLOADING material $id has no uploaded byte count" },
                totalBytes = requireNotNull(uploadTotalBytes) { "UPLOADING material $id has no total byte count" },
            )
        }

        MaterialSyncState.SYNCED -> {
            SyncState.Synced
        }

        MaterialSyncState.FAILED -> {
            SyncState.Failed(
                reason = requireNotNull(failureReason) { "FAILED material $id has no reason" },
                retryable = requireNotNull(failureRetryable) { "FAILED material $id has no retry policy" },
            )
        }
    }

public fun StudyTask.asEntity(): TaskWithReminder =
    TaskWithReminder(
        task = StudyTaskEntity(id, title, notes, subjectId, dueAt, timeZone, completedAt),
        reminder = reminder?.asEntity(id),
    )

private fun Reminder.asEntity(taskId: String): ReminderEntity {
    val recurrenceEnd = recurrence?.end
    return ReminderEntity(
        id = id,
        taskId = taskId,
        leadTime = leadTime,
        precision = precision,
        recurrenceFrequency = recurrence?.frequency,
        recurrenceInterval = recurrence?.interval,
        recurrenceDaysOfWeek = recurrence?.daysOfWeek,
        recurrenceDayOfMonth = recurrence?.dayOfMonth,
        recurrenceEndType = recurrenceEnd?.asEntity(),
        recurrenceEndCount = (recurrenceEnd as? RecurrenceEnd.AfterOccurrences)?.count,
        recurrenceEndDate = (recurrenceEnd as? RecurrenceEnd.OnDate)?.date,
    )
}

public fun TaskWithReminder.asExternalModel(): StudyTask =
    StudyTask(
        id = task.id,
        title = task.title,
        notes = task.notes,
        subjectId = task.subjectId,
        dueAt = task.dueAt,
        timeZone = task.timeZone,
        completedAt = task.completedAt,
        reminder = reminder?.asExternalModel(),
    )

private fun ReminderEntity.asExternalModel(): Reminder =
    Reminder(
        id = id,
        leadTime = leadTime,
        precision = precision,
        recurrence =
            recurrenceFrequency?.let { frequency ->
                RecurrenceRule(
                    frequency = frequency,
                    interval = requireNotNull(recurrenceInterval),
                    daysOfWeek = recurrenceDaysOfWeek.orEmpty(),
                    dayOfMonth = recurrenceDayOfMonth,
                    end = asExternalRecurrenceEnd(),
                )
            },
    )

private fun RecurrenceEnd.asEntity(): RecurrenceEndType =
    when (this) {
        RecurrenceEnd.Never -> RecurrenceEndType.NEVER
        is RecurrenceEnd.AfterOccurrences -> RecurrenceEndType.AFTER_OCCURRENCES
        is RecurrenceEnd.OnDate -> RecurrenceEndType.ON_DATE
    }

private fun ReminderEntity.asExternalRecurrenceEnd(): RecurrenceEnd =
    when (requireNotNull(recurrenceEndType)) {
        RecurrenceEndType.NEVER -> {
            RecurrenceEnd.Never
        }

        RecurrenceEndType.AFTER_OCCURRENCES -> {
            RecurrenceEnd.AfterOccurrences(requireNotNull(recurrenceEndCount))
        }

        RecurrenceEndType.ON_DATE -> {
            RecurrenceEnd.OnDate(requireNotNull(recurrenceEndDate))
        }
    }

public fun StudySession.asEntity(): StudySessionEntity =
    StudySessionEntity(
        id = id,
        subjectId = subjectId,
        note = note,
        status = status,
        startedAt = startedAt,
        endedAt = endedAt,
        deviceId = deviceId,
        updatedAt = updatedAt,
        deleted = deleted,
    )

/**
 * The metadata the event log does not carry, so the projection can be re-derived from the log.
 *
 * Deliberately not a `StudySession` mapping: every timing field of the projection is derived by
 * [dev.studyflow.core.domain.session.SessionReducer], and reading those columns back would make the
 * cache a second source of truth.
 */
public fun StudySessionEntity.asDescriptor(): SessionDescriptor =
    SessionDescriptor(id = id, deviceId = deviceId, subjectId = subjectId, note = note, deleted = deleted)

/** Re-derives the projection by replaying the session's events. */
public fun SessionWithEvents.asExternalModel(): StudySession? =
    SessionReducer.reduce(session.asDescriptor(), events.map(SessionEventEntity::asExternalModel))

public fun SessionEvent.asEntity(): SessionEventEntity =
    SessionEventEntity(id, sessionId, type, anchor.uptime, anchor.wallClock, anchor.bootId, sequence)

public fun SessionEventEntity.asExternalModel(): SessionEvent =
    SessionEvent(
        id = id,
        sessionId = sessionId,
        type = type,
        anchor = TimeAnchor(uptime, wallClock, bootId),
        sequence = sequence,
    )
