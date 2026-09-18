package dev.studyflow.core.testing.data

import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

// Data builders for the shared models.
//
// A test should read as the one fact it is about. Constructing a Material by hand costs eleven
// arguments, ten of which are noise for any given test and all of which have to be revisited when
// the model grows a field; a builder with defaults lets the test name the one value it cares about
// and stay silent about the rest. The defaults are deliberately valid and boring — every model here
// validates itself in `init`, so a builder that returned nonsense would fail in the fixture rather
// than in the assertion.
//
// The instants line up with `FakeDevice`'s default start, so a fixture and a fake clock tell the
// same story without either having to be configured.

/** The wall-clock reading `FakeDevice` starts at. */
public val TEST_WALL_CLOCK: Instant = Instant.parse("2026-03-01T09:00:00Z")

/** The boot epoch `FakeDevice` starts in. */
public val TEST_BOOT_ID: BootId = BootId("boot-0")

/** The device sessions are recorded on; the scope of the "one active session" invariant. */
public const val TEST_DEVICE_ID: String = "device-0"

public fun testSubject(
    id: String = "subject-1",
    name: String = "Mathematics",
    colorArgb: Int = 0xFF3F51B5.toInt(),
    archived: Boolean = false,
): Subject = Subject(id = id, name = name, colorArgb = colorArgb, archived = archived)

@Suppress("LongParameterList")
public fun testStudyTask(
    id: String = "task-1",
    title: String = "Revise integration by parts",
    notes: String? = null,
    subjectId: String? = null,
    materialId: String? = null,
    sessionId: String? = null,
    dueAt: LocalDateTime? = null,
    timeZone: TimeZone = TimeZone.UTC,
    isAllDay: Boolean = false,
    priority: TaskPriority = TaskPriority.NORMAL,
    tags: Set<String> = emptySet(),
    subtasks: List<Subtask> = emptyList(),
    recurrence: RecurrenceRule? = null,
    completedAt: Instant? = null,
    reminders: List<Reminder> = emptyList(),
    updatedAt: Instant = TEST_WALL_CLOCK,
    deleted: Boolean = false,
): StudyTask =
    StudyTask(
        id = id,
        title = title,
        notes = notes,
        subjectId = subjectId,
        materialId = materialId,
        sessionId = sessionId,
        dueAt = dueAt,
        timeZone = timeZone,
        isAllDay = isAllDay,
        priority = priority,
        tags = tags,
        subtasks = subtasks,
        recurrence = recurrence,
        completedAt = completedAt,
        reminders = reminders,
        updatedAt = updatedAt,
        deleted = deleted,
    )

public fun testReminder(
    id: String = "reminder-1",
    taskId: String = "task-1",
    trigger: ReminderTrigger = ReminderTrigger.BeforeDue(),
    precision: ReminderPrecision = ReminderPrecision.GENTLE,
    snooze: SnoozeState? = null,
    lastFiredAt: Instant? = null,
    schedulingId: String? = null,
): Reminder =
    Reminder(
        id = id,
        taskId = taskId,
        trigger = trigger,
        precision = precision,
        snooze = snooze,
        lastFiredAt = lastFiredAt,
        schedulingId = schedulingId,
    )

public fun testStudySession(
    id: String = "session-1",
    taskId: String? = null,
    subjectId: String? = "subject-1",
    note: String? = null,
    startedAt: Instant = TEST_WALL_CLOCK,
    endedAt: Instant? = null,
    status: SessionStatus = SessionStatus.RUNNING,
    elapsed: SessionElapsed = SessionElapsed(counted = Duration.ZERO),
    deviceId: String = TEST_DEVICE_ID,
    updatedAt: Instant = startedAt,
    deleted: Boolean = false,
): StudySession =
    StudySession(
        id = id,
        taskId = taskId,
        subjectId = subjectId,
        note = note,
        startedAt = startedAt,
        endedAt = endedAt,
        status = status,
        elapsed = elapsed,
        deviceId = deviceId,
        updatedAt = updatedAt,
        deleted = deleted,
    )

public fun testTimeAnchor(
    uptime: Duration = Duration.ZERO,
    wallClock: Instant = TEST_WALL_CLOCK,
    bootId: BootId = TEST_BOOT_ID,
): TimeAnchor = TimeAnchor(uptime = uptime, wallClock = wallClock, bootId = bootId)

public fun testSessionEvent(
    id: String = "event-1",
    sessionId: String = "session-1",
    type: SessionEventType = SessionEventType.STARTED,
    anchor: TimeAnchor = testTimeAnchor(),
    sequence: Long = 0,
): SessionEvent = SessionEvent(id = id, sessionId = sessionId, type = type, anchor = anchor, sequence = sequence)

public fun testMaterial(
    id: String = "material-1",
    folderId: String? = null,
    displayName: String = "lecture-notes.pdf",
    mimeType: String = "application/pdf",
    sizeBytes: Long = 1_024,
    contentHash: ContentHash = testContentHash(),
    createdAt: Instant = TEST_WALL_CLOCK,
    sync: SyncState = SyncState.Pending,
    localUri: String? = null,
    pinnedForOffline: Boolean = false,
    encrypted: Boolean = false,
    pageCount: Int? = null,
    duration: Duration? = null,
    previewPageIndex: Int = 0,
    previewPositionMillis: Long = 0,
    playbackSpeed: Float = 1f,
): Material =
    Material(
        id = id,
        folderId = folderId,
        subjectId = null,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        contentHash = contentHash,
        createdAt = createdAt,
        updatedAt = createdAt,
        sync = sync,
        localPath = localUri,
        pinnedForOffline = pinnedForOffline,
        encrypted = encrypted,
        pageCount = pageCount,
        duration = duration,
        previewPageIndex = previewPageIndex,
        previewPositionMillis = previewPositionMillis,
        playbackSpeed = playbackSpeed,
    )

/**
 * A syntactically valid [ContentHash] derived from [seed].
 *
 * Deliberately not a real SHA-256: a fixture only needs two files with different contents to get
 * different digests, and the same seed to get the same digest twice, which is what makes
 * deduplication and idempotent-upload tests possible without hashing anything.
 */
public fun testContentHash(seed: String = "material-1"): ContentHash {
    val digits =
        seed
            .fold(HASH_SEED) { acc, character -> acc * HASH_MULTIPLIER + character.code.toULong() }
            .toString(radix = HEX_RADIX)
    return ContentHash(buildString { while (length < SHA256_HEX_LENGTH) append(digits) }.take(SHA256_HEX_LENGTH))
}

/** Length of a SHA-256 digest in hex characters, which is what [ContentHash] validates. */
public const val SHA256_HEX_LENGTH: Int = 64

private const val HASH_SEED = 1uL
private const val HASH_MULTIPLIER = 31uL
private const val HEX_RADIX = 16
