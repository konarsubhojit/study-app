package dev.studyflow.core.database.entity

import dev.studyflow.core.domain.session.SessionDescriptor
import dev.studyflow.core.domain.session.SessionReducer
import dev.studyflow.core.model.Folder
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.RecurrenceEnd
import dev.studyflow.core.model.RecurrenceRule
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.ReminderTrigger
import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.SnoozeState
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subject
import dev.studyflow.core.model.Subtask
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.model.TimeAnchor
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

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

/**
 * Flattens the task aggregate into its rows.
 *
 * Derived columns (`due_at_utc`, `trigger_at_utc`) are computed here, on the single write path, so
 * they cannot drift from the authored local time and trigger they are derived from.
 */
public fun StudyTask.asEntity(): TaskWithReminders =
    TaskWithReminders(
        task =
            StudyTaskEntity(
                id = id,
                title = title,
                notes = notes,
                subjectId = subjectId,
                materialId = materialId,
                sessionId = sessionId,
                dueAt = dueAt,
                dueAtUtc = dueAtUtc,
                timeZone = timeZone,
                isAllDay = isAllDay,
                priority = priority,
                recurrenceFrequency = recurrence?.frequency,
                recurrenceInterval = recurrence?.interval,
                recurrenceDaysOfWeek = recurrence?.daysOfWeek,
                recurrenceDayOfMonth = recurrence?.dayOfMonth,
                recurrenceWeekOfMonth = recurrence?.weekOfMonth,
                recurrenceMonthOfYear = recurrence?.monthOfYear,
                recurrenceExceptions = recurrence?.exceptions,
                recurrenceEndType = recurrence?.end?.asEntity(),
                recurrenceEndCount = (recurrence?.end as? RecurrenceEnd.AfterOccurrences)?.count,
                recurrenceEndDate = (recurrence?.end as? RecurrenceEnd.OnDate)?.date,
                completedAt = completedAt,
                updatedAt = updatedAt,
                deleted = deleted,
            ),
        reminders = reminders.map { it.asReminderEntity(dueAtUtc) },
        tags = tags.map { TaskTagEntity(taskId = id, tag = it) },
        subtasks =
            subtasks.mapIndexed { position, subtask ->
                SubtaskEntity(
                    id = subtask.id,
                    taskId = id,
                    position = position,
                    title = subtask.title,
                    completedAt = subtask.completedAt,
                )
            },
    )

private fun Reminder.asReminderEntity(dueAtUtc: Instant?): ReminderEntity =
    when (val trigger = trigger) {
        is ReminderTrigger.BeforeDue -> {
            reminderEntity(
                triggerType = ReminderTriggerType.BEFORE_DUE,
                leadTime = trigger.leadTime,
                triggerInstant = null,
                triggerTimeZone = null,
                triggerAtUtc = dueAtUtc?.minus(trigger.leadTime),
            )
        }

        is ReminderTrigger.AtInstant -> {
            reminderEntity(
                triggerType = ReminderTriggerType.AT_INSTANT,
                leadTime = null,
                triggerInstant = trigger.instant,
                triggerTimeZone = trigger.timeZone,
                triggerAtUtc = trigger.instant,
            )
        }
    }

@Suppress("LongParameterList")
private fun Reminder.reminderEntity(
    triggerType: ReminderTriggerType,
    leadTime: Duration?,
    triggerInstant: Instant?,
    triggerTimeZone: TimeZone?,
    triggerAtUtc: Instant?,
): ReminderEntity =
    ReminderEntity(
        id = id,
        taskId = taskId,
        triggerType = triggerType,
        leadTime = leadTime,
        triggerInstant = triggerInstant,
        triggerTimeZone = triggerTimeZone,
        triggerAtUtc = triggerAtUtc,
        precision = precision,
        snoozeUntil = snooze?.until,
        snoozeCount = snooze?.count,
        lastFiredAt = lastFiredAt,
        schedulingId = schedulingId,
    )

public fun TaskWithReminders.asExternalModel(): StudyTask =
    StudyTask(
        id = task.id,
        title = task.title,
        notes = task.notes,
        subjectId = task.subjectId,
        materialId = task.materialId,
        sessionId = task.sessionId,
        dueAt = task.dueAt,
        timeZone = task.timeZone,
        isAllDay = task.isAllDay,
        priority = task.priority,
        tags = tags.mapTo(linkedSetOf(), TaskTagEntity::tag),
        subtasks =
            subtasks
                .sortedBy(SubtaskEntity::position)
                .map { Subtask(id = it.id, title = it.title, completedAt = it.completedAt) },
        recurrence = task.asExternalRecurrence(),
        completedAt = task.completedAt,
        // Room does not promise a relation order. Sorting by id gives a deterministic read; a
        // reminder's position carries no meaning, unlike a subtask's, which has its own column.
        reminders = reminders.sortedBy(ReminderEntity::id).map(ReminderEntity::asExternalModel),
        updatedAt = task.updatedAt,
        deleted = task.deleted,
    )

private fun StudyTaskEntity.asExternalRecurrence(): RecurrenceRule? =
    recurrenceFrequency?.let { frequency ->
        RecurrenceRule(
            frequency = frequency,
            interval = requireNotNull(recurrenceInterval) { "recurring task $id has no interval" },
            daysOfWeek = recurrenceDaysOfWeek.orEmpty(),
            dayOfMonth = recurrenceDayOfMonth,
            weekOfMonth = recurrenceWeekOfMonth,
            monthOfYear = recurrenceMonthOfYear,
            exceptions = recurrenceExceptions.orEmpty(),
            end = asExternalRecurrenceEnd(),
        )
    }

public fun ReminderEntity.asExternalModel(): Reminder =
    Reminder(
        id = id,
        taskId = taskId,
        trigger =
            when (triggerType) {
                ReminderTriggerType.BEFORE_DUE -> {
                    ReminderTrigger.BeforeDue(
                        leadTime = requireNotNull(leadTime) { "relative reminder $id has no lead time" },
                    )
                }

                ReminderTriggerType.AT_INSTANT -> {
                    ReminderTrigger.AtInstant(
                        instant = requireNotNull(triggerInstant) { "absolute reminder $id has no instant" },
                        timeZone = requireNotNull(triggerTimeZone) { "absolute reminder $id has no time zone" },
                    )
                }
            },
        precision = precision,
        snooze =
            snoozeUntil?.let { until ->
                SnoozeState(
                    until = until,
                    count = requireNotNull(snoozeCount) { "snoozed reminder $id has no snooze count" },
                )
            },
        lastFiredAt = lastFiredAt,
        schedulingId = schedulingId,
    )

private fun RecurrenceEnd.asEntity(): RecurrenceEndType =
    when (this) {
        RecurrenceEnd.Never -> RecurrenceEndType.NEVER
        is RecurrenceEnd.AfterOccurrences -> RecurrenceEndType.AFTER_OCCURRENCES
        is RecurrenceEnd.OnDate -> RecurrenceEndType.ON_DATE
    }

private fun StudyTaskEntity.asExternalRecurrenceEnd(): RecurrenceEnd =
    when (requireNotNull(recurrenceEndType) { "recurring task $id has no end type" }) {
        RecurrenceEndType.NEVER -> {
            RecurrenceEnd.Never
        }

        RecurrenceEndType.AFTER_OCCURRENCES -> {
            RecurrenceEnd.AfterOccurrences(requireNotNull(recurrenceEndCount) { "task $id has no occurrence count" })
        }

        RecurrenceEndType.ON_DATE -> {
            RecurrenceEnd.OnDate(requireNotNull(recurrenceEndDate) { "task $id has no recurrence end date" })
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
