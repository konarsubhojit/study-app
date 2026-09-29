package dev.studyflow.core.domain.sync

import dev.studyflow.core.domain.lifecycle.ArchiveFormatException
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.Reminder
import dev.studyflow.core.model.ReminderTrigger
import dev.studyflow.core.model.SnoozeState
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subtask
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.testing.data.testContentHash
import dev.studyflow.core.testing.data.testMaterial
import dev.studyflow.core.testing.data.testReminder
import dev.studyflow.core.testing.data.testStudyTask
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The task and material conflict policy and wire format (ADR 0018).
 *
 * The two properties that matter most are determinism — both devices must pick the same winner —
 * and that device-local state (alarms, downloads, offline pins) never crosses the wire, because a
 * second device replaying the first device's alarms is the failure users would notice.
 */
@DisplayName("DocumentSyncMerge")
class DocumentSyncMergeTest {
    @Nested
    @DisplayName("last write wins")
    inner class Conflicts {
        @Test
        fun `the later write wins in either direction`() {
            val older = task(updatedAt = T0, device = "device-b", title = "Old")
            val newer = task(updatedAt = T1, device = "device-a", title = "New")

            assertInstanceOf(
                DocumentMergeOutcome.Replaced::class.java,
                DocumentSyncMerge.merge(local = older, remote = newer, receivedAt = T1),
            )
            assertEquals(
                DocumentMergeOutcome.KeptLocal,
                DocumentSyncMerge.merge(local = newer, remote = older, receivedAt = T1),
            )
        }

        @Test
        fun `a tie on updatedAt goes to the greater device id on both devices`() {
            val fromA = task(updatedAt = T0, device = "device-a", title = "A")
            val fromB = task(updatedAt = T0, device = "device-b", title = "B")

            assertInstanceOf(
                DocumentMergeOutcome.Replaced::class.java,
                DocumentSyncMerge.merge(local = fromA, remote = fromB, receivedAt = T1),
            )
            assertEquals(
                DocumentMergeOutcome.KeptLocal,
                DocumentSyncMerge.merge(local = fromB, remote = fromA, receivedAt = T1),
            )
        }

        @Test
        fun `a tombstone beats a live copy of the same write`() {
            val live = task(updatedAt = T0, device = "device-a")
            val tombstone = SyncTaskRecord(live.task.copy(deleted = true), "device-a")

            assertInstanceOf(
                DocumentMergeOutcome.Replaced::class.java,
                DocumentSyncMerge.merge(local = live, remote = tombstone, receivedAt = T1),
            )
            assertEquals(
                DocumentMergeOutcome.KeptLocal,
                DocumentSyncMerge.merge(local = tombstone, remote = live, receivedAt = T1),
            )
        }
    }

    @Nested
    @DisplayName("device-local state")
    inner class DeviceLocal {
        @Test
        fun `a received reminder keeps this device's alarm and snooze`() {
            val snooze = SnoozeState(until = T1 + 10.minutes)
            val local =
                task(
                    updatedAt = T0,
                    device = "device-a",
                    reminders =
                        listOf(
                            testReminder(snooze = snooze, lastFiredAt = T1 + 5.minutes, schedulingId = "alarm-7"),
                        ),
                )
            val remote = task(updatedAt = T1, device = "device-b", reminders = listOf(testReminder()))

            val merged = replacedTask(DocumentSyncMerge.merge(local, remote, receivedAt = T1)).reminders.single()

            assertEquals(snooze, merged.snooze)
            assertEquals("alarm-7", merged.schedulingId)
            assertEquals(T1 + 5.minutes, merged.lastFiredAt)
        }

        @Test
        fun `a reminder new to this device is treated as fired up to the moment it arrived`() {
            val remote = task(updatedAt = T0, device = "device-b", reminders = listOf(testReminder()))

            val merged = replacedTask(DocumentSyncMerge.merge(local = null, remote = remote, receivedAt = T1))

            assertEquals(T1, merged.reminders.single().lastFiredAt)
        }

        @Test
        fun `a material keeps its download, pin and preview when the bytes are unchanged`() {
            val local =
                material(
                    updatedAt = T0,
                    device = "device-a",
                    base =
                        testMaterial(
                            sync = SyncState.Synced,
                            localUri = "file:///notes.pdf",
                            pinnedForOffline = true,
                            previewPageIndex = 4,
                            playbackSpeed = 1.5f,
                        ),
                )
            val remote =
                material(
                    updatedAt = T1,
                    device = "device-b",
                    base = testMaterial(sync = SyncState.Synced).copy(displayName = "renamed.pdf"),
                )

            val merged = replacedMaterial(DocumentSyncMerge.merge(local, remote, receivedAt = T1))

            assertEquals("renamed.pdf", merged.displayName)
            assertEquals("file:///notes.pdf", merged.localPath)
            assertTrue(merged.pinnedForOffline)
            assertEquals(4, merged.previewPageIndex)
            assertEquals(1.5f, merged.playbackSpeed)
            assertEquals(SyncState.Synced, merged.sync)
        }

        @Test
        fun `a download of different bytes no longer describes the record`() {
            val local = material(T0, "device-a", testMaterial(sync = SyncState.Synced, localUri = "file:///old.pdf"))
            val remote =
                material(T1, "device-b", testMaterial(sync = SyncState.Synced, contentHash = testContentHash("other")))

            assertNull(replacedMaterial(DocumentSyncMerge.merge(local, remote, receivedAt = T1)).localPath)
        }

        @Test
        fun `a material without an uploaded object is refused`() {
            val remote = SyncMaterialRecord(testMaterial(sync = SyncState.Synced), "device-b")

            assertInstanceOf(
                DocumentMergeOutcome.Rejected::class.java,
                DocumentSyncMerge.merge(local = null, remote = remote, receivedAt = T1),
            )
        }

        @Test
        fun `only an uploaded material is syncable`() {
            assertFalse(DocumentSyncMerge.isSyncable(testMaterial(sync = SyncState.Pending).copy(remoteKey = "k")))
            assertFalse(DocumentSyncMerge.isSyncable(testMaterial(sync = SyncState.Synced)))
            assertTrue(DocumentSyncMerge.isSyncable(testMaterial(sync = SyncState.Synced).copy(remoteKey = "k")))
        }
    }

    @Nested
    @DisplayName("wire format")
    inner class Codec {
        @Test
        fun `a task round-trips without its device-local reminder state`() {
            val original =
                testStudyTask(
                    id = "task-1",
                    dueAt = LocalDateTime(2026, 3, 2, 9, 0),
                    tags = setOf("exam"),
                    subtasks = listOf(Subtask(id = "sub-1", title = "Read chapter")),
                    reminders =
                        listOf(
                            testReminder(
                                trigger = ReminderTrigger.BeforeDue(),
                                lastFiredAt = T0,
                                schedulingId = "alarm-1",
                                snooze = SnoozeState(until = T1),
                            ),
                        ),
                    updatedAt = T1,
                )

            val decoded = roundTrip(SyncTaskRecord(original, "device-a")) as SyncTaskRecord

            val stripped =
                original.copy(
                    reminders =
                        original.reminders.map {
                            it.copy(
                                lastFiredAt = null,
                                schedulingId = null,
                                snooze = null,
                            )
                        },
                )
            assertEquals(stripped, decoded.task)
            assertEquals("device-a", decoded.deviceId)
        }

        @Test
        fun `a material round-trips as uploaded and without this device's file`() {
            val original =
                testMaterial(sync = SyncState.Synced, localUri = "file:///a.pdf", pinnedForOffline = true)
                    .copy(remoteKey = "owner/hash", updatedAt = T1)

            val decoded = (roundTrip(SyncMaterialRecord(original, "device-a")) as SyncMaterialRecord).material

            assertEquals(original.copy(localPath = null, pinnedForOffline = false), decoded)
        }

        @Test
        fun `the envelope is authoritative over the payload`() {
            val payload = SyncDocumentCodec.encode(task(updatedAt = T0, device = "device-a"))

            val decoded =
                SyncDocumentCodec.decode(
                    SyncEntityType.TASK,
                    "task-1",
                    "device-a",
                    T1,
                    true,
                    1,
                    payload,
                ) as SyncTaskRecord

            assertEquals(T1, decoded.task.updatedAt)
            assertTrue(decoded.task.deleted)
        }

        @Test
        fun `a newer schema version or a malformed payload is refused`() {
            val payload = SyncDocumentCodec.encode(task(updatedAt = T0, device = "device-a"))

            assertThrows<ArchiveFormatException> {
                SyncDocumentCodec.decode(
                    SyncEntityType.TASK,
                    "task-1",
                    "d",
                    T0,
                    false,
                    SyncDocumentCodec.SCHEMA_VERSION + 1,
                    payload,
                )
            }
            assertThrows<ArchiveFormatException> {
                SyncDocumentCodec.decode(
                    SyncEntityType.TASK,
                    "task-1",
                    "d",
                    T0,
                    false,
                    1,
                    JsonObject(
                        mapOf("title" to JsonPrimitive(3)),
                    ),
                )
            }
        }

        private fun roundTrip(document: SyncDocument): SyncDocument =
            SyncDocumentCodec.decode(
                entityType = document.entityType,
                id = document.id,
                deviceId = document.deviceId,
                updatedAt = document.updatedAt,
                deleted = document.deleted,
                schemaVersion = SyncDocumentCodec.SCHEMA_VERSION,
                payload = SyncDocumentCodec.encode(document),
            )
    }

    private fun task(
        updatedAt: Instant,
        device: String,
        title: String = "Revise",
        reminders: List<Reminder> = emptyList(),
    ) = SyncTaskRecord(
        testStudyTask(
            title = title,
            dueAt = LocalDateTime(2026, 3, 2, 9, 0),
            reminders = reminders,
            updatedAt = updatedAt,
        ),
        device,
    )

    private fun material(
        updatedAt: Instant,
        device: String,
        base: Material,
    ) = SyncMaterialRecord(base.copy(remoteKey = "owner/hash", updatedAt = updatedAt), device)

    private fun replacedTask(outcome: DocumentMergeOutcome): StudyTask =
        (assertInstanceOf(DocumentMergeOutcome.Replaced::class.java, outcome).document as SyncTaskRecord).task

    private fun replacedMaterial(outcome: DocumentMergeOutcome): Material =
        (assertInstanceOf(DocumentMergeOutcome.Replaced::class.java, outcome).document as SyncMaterialRecord).material

    private companion object {
        val T0: Instant = Instant.parse("2026-03-01T10:00:00Z")
        val T1: Instant = Instant.parse("2026-03-01T11:00:00Z")
    }
}
