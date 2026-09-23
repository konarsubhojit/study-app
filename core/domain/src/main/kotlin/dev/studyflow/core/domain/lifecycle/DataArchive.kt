package dev.studyflow.core.domain.lifecycle

import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.RecurrenceEnd
import dev.studyflow.core.model.RecurrenceFrequency
import dev.studyflow.core.model.RecurrenceRule
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.ReminderPrecision
import dev.studyflow.core.model.ReminderTrigger
import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.SessionEventType
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.SnoozeState
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subject
import dev.studyflow.core.model.Subtask
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.model.TaskPriority
import dev.studyflow.core.model.TimeAnchor
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The user's data, in the shape it leaves the device in (issue #78).
 *
 * This is a deliberately separate wire model rather than `@Serializable` domain classes. An export
 * a student keeps on a laptop for two years has to stay readable by an app that has refactored its
 * models half a dozen times since, so the format is pinned here, versioned by [schemaVersion], and
 * changed only by a conscious edit of this file plus a bump of [CURRENT_SCHEMA_VERSION].
 *
 * Every field is a primitive or a string. Instants are ISO-8601 (`2026-09-23T02:49:21Z`) and
 * durations are whole milliseconds, so the file is legible in a text editor — part of the point of
 * giving the user their data is that they can actually read it — and independent of whichever
 * serializers a date-time library happens to ship.
 *
 * Material *bytes* are not in here: they travel as separate entries in the surrounding archive,
 * named by [ArchivedMaterial.archiveEntry], which keeps a JSON parse cheap however many gigabytes
 * of lecture recordings the export contains.
 *
 * @property exportedAt when the export ran, for the user's benefit and for support tickets.
 * @property deviceId the device the export was taken on; imported sessions are re-homed rather
 *   than pretending to be live on the importing device.
 */
@Serializable
public data class DataArchive(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAt: String,
    val deviceId: String,
    val subjects: List<ArchivedSubject> = emptyList(),
    val sessions: List<ArchivedSession> = emptyList(),
    val tasks: List<ArchivedTask> = emptyList(),
    val materials: List<ArchivedMaterial> = emptyList(),
) {
    /** Total number of records, for the "exported N items" summary. */
    public val recordCount: Int get() = subjects.size + sessions.size + tasks.size + materials.size
}

/** The archive format this build writes, and the newest one it knows how to read. */
public const val CURRENT_SCHEMA_VERSION: Int = 1

/** Entry name of the JSON document inside the archive. */
public const val ARCHIVE_DOCUMENT_ENTRY: String = "studyflow-archive.json"

/** Directory inside the archive holding the original cached material files. */
public const val ARCHIVE_MATERIALS_DIRECTORY: String = "materials"

@Serializable
public data class ArchivedSubject(
    val id: String,
    val name: String,
    val colorArgb: Int,
    val archived: Boolean = false,
)

/**
 * A session projection together with the event log it is derived from.
 *
 * The log travels with the projection because it *is* the session: a restore that kept only the
 * projection would hand the user a number nobody can re-derive or audit (ADR 0003).
 */
@Serializable
public data class ArchivedSession(
    val id: String,
    val taskId: String? = null,
    val subjectId: String? = null,
    val note: String? = null,
    val startedAt: String,
    val endedAt: String? = null,
    val status: String,
    val countedMillis: Long,
    val unverifiedMillis: Long,
    val deviceId: String,
    val updatedAt: String,
    val deleted: Boolean = false,
    val manualOverride: Boolean = false,
    val events: List<ArchivedSessionEvent> = emptyList(),
)

@Serializable
public data class ArchivedSessionEvent(
    val id: String,
    val type: String,
    val uptimeMillis: Long,
    val wallClock: String,
    val bootId: String,
    val sequence: Long,
)

@Serializable
public data class ArchivedTask(
    val id: String,
    val title: String,
    val notes: String? = null,
    val subjectId: String? = null,
    val materialId: String? = null,
    val sessionId: String? = null,
    val dueAt: String? = null,
    val timeZone: String = "UTC",
    val isAllDay: Boolean = false,
    val priority: String = TaskPriority.NORMAL.name,
    val tags: List<String> = emptyList(),
    val subtasks: List<ArchivedSubtask> = emptyList(),
    val recurrence: ArchivedRecurrence? = null,
    val completedAt: String? = null,
    val reminders: List<ArchivedReminder> = emptyList(),
    val updatedAt: String,
    val deleted: Boolean = false,
)

@Serializable
public data class ArchivedSubtask(
    val id: String,
    val title: String,
    val completedAt: String? = null,
)

@Serializable
public data class ArchivedReminder(
    val id: String,
    val trigger: ArchivedReminderTrigger,
    val precision: String = ReminderPrecision.GENTLE.name,
    val snoozeUntil: String? = null,
    val snoozeCount: Int? = null,
    val lastFiredAt: String? = null,
    val schedulingId: String? = null,
)

/**
 * A reminder's anchor, as a tagged union.
 *
 * @property leadTimeMillis set for a "before due" trigger; [instant] is set for an absolute one.
 */
@Serializable
public data class ArchivedReminderTrigger(
    val kind: String,
    val leadTimeMillis: Long? = null,
    val instant: String? = null,
    val timeZone: String? = null,
) {
    public companion object {
        public const val BEFORE_DUE: String = "beforeDue"
        public const val AT_INSTANT: String = "atInstant"
    }
}

@Serializable
public data class ArchivedRecurrence(
    val frequency: String,
    val interval: Int = 1,
    val daysOfWeek: List<Int> = emptyList(),
    val dayOfMonth: Int? = null,
    val weekOfMonth: Int? = null,
    val monthOfYear: Int? = null,
    val exceptions: List<String> = emptyList(),
    val endKind: String = RECURRENCE_END_NEVER,
    val endCount: Int? = null,
    val endDate: String? = null,
)

internal const val RECURRENCE_END_NEVER: String = "never"
internal const val RECURRENCE_END_AFTER: String = "afterOccurrences"
internal const val RECURRENCE_END_ON_DATE: String = "onDate"

/**
 * Catalogue metadata for one material.
 *
 * @property archiveEntry path of the file's bytes inside the archive, or `null` when the device
 *   held no cached copy to export. A metadata-only row is still worth restoring: it keeps the
 *   material's name, notes and subject, and the bytes can be fetched again from the server.
 */
@Serializable
public data class ArchivedMaterial(
    val id: String,
    val folderId: String? = null,
    val subjectId: String? = null,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentHash: String,
    val createdAt: String,
    val updatedAt: String,
    val notes: String? = null,
    val remoteKey: String? = null,
    val pinnedForOffline: Boolean = false,
    val encrypted: Boolean = false,
    val deleted: Boolean = false,
    val pageCount: Int? = null,
    val durationMillis: Long? = null,
    @SerialName("archiveEntry")
    val archiveEntry: String? = null,
)

/** One exported session: the projection plus the log that produced it. */
public data class SessionWithLog(
    val session: StudySession,
    val events: List<SessionEvent>,
)

public fun Subject.toArchived(): ArchivedSubject = ArchivedSubject(id, name, colorArgb, archived)

/** @throws ArchiveFormatException when the record cannot produce a valid [Subject]. */
public fun ArchivedSubject.toModel(): Subject = archiveMapping(id) { Subject(id, name, colorArgb, archived) }

public fun SessionWithLog.toArchived(): ArchivedSession =
    ArchivedSession(
        id = session.id,
        taskId = session.taskId,
        subjectId = session.subjectId,
        note = session.note,
        startedAt = session.startedAt.toString(),
        endedAt = session.endedAt?.toString(),
        status = session.status.name,
        countedMillis = session.elapsed.counted.inWholeMilliseconds,
        unverifiedMillis = session.elapsed.unverified.inWholeMilliseconds,
        deviceId = session.deviceId,
        updatedAt = session.updatedAt.toString(),
        deleted = session.deleted,
        manualOverride = session.manualOverride,
        events = events.map(SessionEvent::toArchived),
    )

public fun SessionEvent.toArchived(): ArchivedSessionEvent =
    ArchivedSessionEvent(
        id = id,
        type = type.name,
        uptimeMillis = anchor.uptime.inWholeMilliseconds,
        wallClock = anchor.wallClock.toString(),
        bootId = anchor.bootId.value,
        sequence = sequence,
    )

/**
 * Rebuilds the session and its log.
 *
 * @throws ArchiveFormatException when a field the model requires is missing or unparseable.
 */
public fun ArchivedSession.toModel(): SessionWithLog =
    archiveMapping(id) {
        SessionWithLog(
            session =
                StudySession(
                    id = id,
                    taskId = taskId,
                    subjectId = subjectId,
                    note = note,
                    startedAt = startedAt.toInstant(),
                    endedAt = endedAt?.toInstant(),
                    status = enumValueOf<SessionStatus>(status),
                    elapsed =
                        SessionElapsed(
                            counted = countedMillis.milliseconds,
                            unverified = unverifiedMillis.milliseconds,
                        ),
                    deviceId = deviceId,
                    updatedAt = updatedAt.toInstant(),
                    deleted = deleted,
                    manualOverride = manualOverride,
                ),
            events =
                events.map { event ->
                    SessionEvent(
                        id = event.id,
                        sessionId = id,
                        type = enumValueOf<SessionEventType>(event.type),
                        anchor =
                            TimeAnchor(
                                uptime = event.uptimeMillis.milliseconds,
                                wallClock = event.wallClock.toInstant(),
                                bootId = BootId(event.bootId),
                            ),
                        sequence = event.sequence,
                    )
                },
        )
    }

public fun StudyTask.toArchived(): ArchivedTask =
    ArchivedTask(
        id = id,
        title = title,
        notes = notes,
        subjectId = subjectId,
        materialId = materialId,
        sessionId = sessionId,
        dueAt = dueAt?.toString(),
        timeZone = timeZone.id,
        isAllDay = isAllDay,
        priority = priority.name,
        tags = tags.sorted(),
        subtasks = subtasks.map { ArchivedSubtask(it.id, it.title, it.completedAt?.toString()) },
        recurrence = recurrence?.toArchived(),
        completedAt = completedAt?.toString(),
        reminders = reminders.map(Reminder::toArchived),
        updatedAt = updatedAt.toString(),
        deleted = deleted,
    )

/**
 * Rebuilds the task aggregate.
 *
 * @throws ArchiveFormatException when the archived record cannot produce a valid [StudyTask] —
 *   an all-day task without a due date, for instance, which the model refuses to construct.
 */
public fun ArchivedTask.toModel(): StudyTask =
    archiveMapping(id) {
        StudyTask(
            id = id,
            title = title,
            notes = notes,
            subjectId = subjectId,
            materialId = materialId,
            sessionId = sessionId,
            dueAt = dueAt?.let(LocalDateTime::parse),
            timeZone = TimeZone.of(timeZone),
            isAllDay = isAllDay,
            priority = enumValueOf<TaskPriority>(priority),
            tags = tags.toSet(),
            subtasks = subtasks.map { Subtask(it.id, it.title, it.completedAt?.toInstant()) },
            recurrence = recurrence?.toModel(),
            completedAt = completedAt?.toInstant(),
            reminders = reminders.map { reminder -> reminder.toReminder(id) },
            updatedAt = updatedAt.toInstant(),
            deleted = deleted,
        )
    }

public fun Material.toArchived(archiveEntry: String?): ArchivedMaterial =
    ArchivedMaterial(
        id = id,
        folderId = folderId,
        subjectId = subjectId,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        contentHash = contentHash.hex,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        notes = notes,
        remoteKey = remoteKey,
        pinnedForOffline = pinnedForOffline,
        encrypted = encrypted,
        deleted = deleted,
        pageCount = pageCount,
        durationMillis = duration?.inWholeMilliseconds,
        archiveEntry = archiveEntry,
    )

/**
 * Rebuilds catalogue metadata.
 *
 * @param localPath where the restored bytes landed, or `null` when the archive carried none. The
 *   sync state is deliberately reset to [SyncState.Pending] rather than restored: whether the
 *   *importing* account has this file in the cloud is a question only the next sync can answer.
 * @throws ArchiveFormatException when the record cannot produce a valid [Material].
 */
public fun ArchivedMaterial.toModel(localPath: String?): Material =
    archiveMapping(id) {
        Material(
            id = id,
            folderId = folderId,
            subjectId = subjectId,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            contentHash = ContentHash(contentHash),
            createdAt = createdAt.toInstant(),
            updatedAt = updatedAt.toInstant(),
            notes = notes,
            remoteKey = remoteKey,
            sync = SyncState.Pending,
            localPath = localPath,
            pinnedForOffline = pinnedForOffline,
            encrypted = encrypted,
            deleted = deleted,
            pageCount = pageCount,
            duration = durationMillis?.milliseconds,
        )
    }

private fun Reminder.toArchived(): ArchivedReminder =
    ArchivedReminder(
        id = id,
        trigger = trigger.toArchived(),
        precision = precision.name,
        snoozeUntil = snooze?.until?.toString(),
        snoozeCount = snooze?.count,
        lastFiredAt = lastFiredAt?.toString(),
        schedulingId = schedulingId,
    )

private fun ArchivedReminder.toReminder(taskId: String): Reminder =
    Reminder(
        id = id,
        taskId = taskId,
        trigger = trigger.toModel(),
        precision = enumValueOf<ReminderPrecision>(precision),
        snooze = snoozeUntil?.let { SnoozeState(it.toInstant(), snoozeCount ?: 1) },
        lastFiredAt = lastFiredAt?.toInstant(),
        schedulingId = schedulingId,
    )

private fun ReminderTrigger.toArchived(): ArchivedReminderTrigger =
    when (this) {
        is ReminderTrigger.BeforeDue -> {
            ArchivedReminderTrigger(
                kind = ArchivedReminderTrigger.BEFORE_DUE,
                leadTimeMillis = leadTime.inWholeMilliseconds,
            )
        }

        is ReminderTrigger.AtInstant -> {
            ArchivedReminderTrigger(
                kind = ArchivedReminderTrigger.AT_INSTANT,
                instant = instant.toString(),
                timeZone = timeZone.id,
            )
        }
    }

private fun ArchivedReminderTrigger.toModel(): ReminderTrigger =
    when (kind) {
        ArchivedReminderTrigger.AT_INSTANT -> {
            ReminderTrigger.AtInstant(
                instant = requireArchived(instant, "reminder trigger instant").toInstant(),
                timeZone = timeZone?.let(TimeZone::of) ?: TimeZone.UTC,
            )
        }

        ArchivedReminderTrigger.BEFORE_DUE -> {
            ReminderTrigger.BeforeDue((leadTimeMillis ?: 0L).milliseconds)
        }

        else -> {
            throw ArchiveFormatException("unknown reminder trigger '$kind'")
        }
    }

private fun RecurrenceRule.toArchived(): ArchivedRecurrence =
    ArchivedRecurrence(
        frequency = frequency.name,
        interval = interval,
        daysOfWeek = daysOfWeek.map { it.isoDayNumber() }.sorted(),
        dayOfMonth = dayOfMonth,
        weekOfMonth = weekOfMonth,
        monthOfYear = monthOfYear,
        exceptions = exceptions.map(LocalDate::toString).sorted(),
        endKind =
            when (end) {
                RecurrenceEnd.Never -> RECURRENCE_END_NEVER
                is RecurrenceEnd.AfterOccurrences -> RECURRENCE_END_AFTER
                is RecurrenceEnd.OnDate -> RECURRENCE_END_ON_DATE
            },
        endCount = (end as? RecurrenceEnd.AfterOccurrences)?.count,
        endDate = (end as? RecurrenceEnd.OnDate)?.date?.toString(),
    )

private fun ArchivedRecurrence.toModel(): RecurrenceRule =
    RecurrenceRule(
        frequency = enumValueOf<RecurrenceFrequency>(frequency),
        interval = interval,
        daysOfWeek = daysOfWeek.map(::isoDayOfWeek).toSet(),
        dayOfMonth = dayOfMonth,
        weekOfMonth = weekOfMonth,
        monthOfYear = monthOfYear,
        exceptions = exceptions.map(LocalDate::parse).toSet(),
        end =
            when (endKind) {
                RECURRENCE_END_AFTER -> {
                    RecurrenceEnd.AfterOccurrences(requireArchived(endCount, "recurrence end count"))
                }

                RECURRENCE_END_ON_DATE -> {
                    RecurrenceEnd.OnDate(LocalDate.parse(requireArchived(endDate, "recurrence end date")))
                }

                else -> {
                    RecurrenceEnd.Never
                }
            },
    )

/** ISO-8601 weekday number (Monday is 1), the only weekday spelling the format commits to. */
private fun DayOfWeek.isoDayNumber(): Int = ordinal + 1

private fun isoDayOfWeek(number: Int): DayOfWeek =
    DayOfWeek.entries.getOrNull(number - 1) ?: throw ArchiveFormatException("unknown weekday '$number'")

private fun String.toInstant(): Instant = Instant.parse(this)

private fun <T : Any> requireArchived(
    value: T?,
    what: String,
): T = value ?: throw ArchiveFormatException("missing $what")

/**
 * Turns a model's own validation into the archive's error vocabulary.
 *
 * The models validate themselves in `init`, which is exactly what should reject a corrupt or
 * hand-edited archive — but as an [ArchiveFormatException] naming the record, not as an
 * `IllegalArgumentException` from somewhere deep in a constructor.
 */
private inline fun <T> archiveMapping(
    recordId: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (invalid: IllegalArgumentException) {
        throw ArchiveFormatException("record '$recordId' is not valid: ${invalid.message}", invalid)
    }

/** The archive could not be read: wrong version, malformed JSON, or an impossible record. */
public class ArchiveFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
