package dev.studyflow.core.domain.session

import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK
import dev.studyflow.core.testing.data.testStudySession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Every rule here exists to protect a human decision, not a measurement: unlike
 * [SessionReducerTest], there is no event log to replay against, so the fixtures build
 * [StudySession] rows directly and assert on the rows [SessionHistoryEditor] hands back.
 */
@DisplayName("SessionHistoryEditor")
class SessionHistoryEditorTest {
    @Nested
    @DisplayName("editing timing")
    inner class EditingTiming {
        @Test
        fun `a stopped session's timing can be corrected`() {
            val session = stoppedSession()
            val newStart = TEST_WALL_CLOCK + 5.minutes
            val newEnd = newStart + 30.minutes

            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.EditTiming(session.id, newStart, newEnd),
                    sessions = mapOf(session.id to session),
                    correctionId = "correction-1",
                    at = AT,
                )

            val applied = result.assertApplied()
            val updated = applied.sessions.single()
            assertEquals(newStart, updated.startedAt)
            assertEquals(newEnd, updated.endedAt)
            assertEquals(30.minutes, updated.elapsed.counted)
            assertTrue(updated.manualOverride, "an edited session's timing no longer matches its own log")
            assertEquals(SessionCorrectionType.EDIT_TIMING, applied.correction.type)
            assertEquals(
                session.asSnapshot(),
                applied.correction.changes
                    .single()
                    .before,
            )
        }

        @Test
        fun `a running session's timing cannot be corrected without stopping it first`() {
            val session = stoppedSession().copy(status = SessionStatus.RUNNING, endedAt = null)

            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.EditTiming(session.id, TEST_WALL_CLOCK, TEST_WALL_CLOCK + 1.minutes),
                    sessions = mapOf(session.id to session),
                    correctionId = "correction-1",
                    at = AT,
                )

            assertEquals(HistoryRejection.SESSION_NOT_STOPPED, result.assertRejected())
        }

        @Test
        fun `an end at or before the start is refused`() {
            val session = stoppedSession()

            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.EditTiming(session.id, session.startedAt, session.startedAt),
                    sessions = mapOf(session.id to session),
                    correctionId = "correction-1",
                    at = AT,
                )

            assertEquals(HistoryRejection.INVALID_TIMING, result.assertRejected())
        }

        @Test
        fun `a deleted session cannot be edited`() {
            val session = stoppedSession().copy(deleted = true)

            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.EditTiming(session.id, TEST_WALL_CLOCK, TEST_WALL_CLOCK + 1.minutes),
                    sessions = mapOf(session.id to session),
                    correctionId = "correction-1",
                    at = AT,
                )

            assertEquals(HistoryRejection.SESSION_DELETED, result.assertRejected())
        }

        @Test
        fun `an unknown session is refused rather than silently ignored`() {
            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.EditTiming("missing", TEST_WALL_CLOCK, TEST_WALL_CLOCK + 1.minutes),
                    sessions = emptyMap(),
                    correctionId = "correction-1",
                    at = AT,
                )

            assertEquals(HistoryRejection.SESSION_NOT_FOUND, result.assertRejected())
        }
    }

    @Nested
    @DisplayName("editing subject and note")
    inner class EditingMetadata {
        @Test
        fun `subject can be reassigned on a running session, since timing is untouched`() {
            val session = stoppedSession().copy(status = SessionStatus.RUNNING, endedAt = null)

            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.EditSubject(session.id, "subject-2"),
                    sessions = mapOf(session.id to session),
                    correctionId = "correction-1",
                    at = AT,
                )

            val updated = result.assertApplied().sessions.single()
            assertEquals("subject-2", updated.subjectId)
            assertFalse(updated.manualOverride, "reassigning a subject does not touch timing")
        }

        @Test
        fun `note can be replaced`() {
            val session = stoppedSession()

            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.EditNote(session.id, "revised note"),
                    sessions = mapOf(session.id to session),
                    correctionId = "correction-1",
                    at = AT,
                )

            assertEquals(
                "revised note",
                result
                    .assertApplied()
                    .sessions
                    .single()
                    .note,
            )
        }
    }

    @Nested
    @DisplayName("splitting")
    inner class Splitting {
        @Test
        fun `a session splits into two adjacent halves that sum to the original`() {
            val session = stoppedSession(duration = 60.minutes)
            val splitAt = session.startedAt + 20.minutes

            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.Split(session.id, splitAt, "session-2"),
                    sessions = mapOf(session.id to session),
                    correctionId = "correction-1",
                    at = AT,
                )

            val applied = result.assertApplied()
            val (first, second) = applied.sessions
            assertEquals(session.startedAt, first.startedAt)
            assertEquals(splitAt, first.endedAt)
            assertEquals(20.minutes, first.elapsed.counted)
            assertEquals(splitAt, second.startedAt)
            assertEquals(session.endedAt, second.endedAt)
            assertEquals(40.minutes, second.elapsed.counted)
            assertEquals(session.elapsed.counted, first.elapsed.counted + second.elapsed.counted)
            assertEquals(session.subjectId, second.subjectId)
            assertTrue(second.manualOverride)
            assertNull(
                applied.correction.changes
                    .last()
                    .before,
                "the second half never existed before the split",
            )
        }

        @Test
        fun `a split point outside the session's bounds is refused`() {
            val session = stoppedSession(duration = 60.minutes)

            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.Split(session.id, session.endedAt!! + 1.minutes, "session-2"),
                    sessions = mapOf(session.id to session),
                    correctionId = "correction-1",
                    at = AT,
                )

            assertEquals(HistoryRejection.SPLIT_POINT_OUT_OF_BOUNDS, result.assertRejected())
        }

        @Test
        fun `a split point exactly on a boundary is refused, since one half would be empty`() {
            val session = stoppedSession(duration = 60.minutes)

            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.Split(session.id, session.startedAt, "session-2"),
                    sessions = mapOf(session.id to session),
                    correctionId = "correction-1",
                    at = AT,
                )

            assertEquals(HistoryRejection.SPLIT_POINT_OUT_OF_BOUNDS, result.assertRejected())
        }
    }

    @Nested
    @DisplayName("merging")
    inner class Merging {
        @Test
        fun `two sessions of the same subject merge into the earliest, and the rest are tombstoned`() {
            val first = stoppedSession(id = "session-1", duration = 30.minutes)
            val second =
                stoppedSession(
                    id = "session-2",
                    startedAt = first.endedAt!! + 10.minutes,
                    duration = 20.minutes,
                )

            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.Merge(listOf(second.id, first.id)),
                    sessions = mapOf(first.id to first, second.id to second),
                    correctionId = "correction-1",
                    at = AT,
                )

            val applied = result.assertApplied()
            val merged = applied.sessions.single { it.id == first.id }
            val tombstoned = applied.sessions.single { it.id == second.id }
            assertEquals(first.startedAt, merged.startedAt)
            assertEquals(second.endedAt, merged.endedAt)
            // The gap between the sessions is not counted as study time.
            assertEquals(50.minutes, merged.elapsed.counted)
            assertTrue(merged.manualOverride)
            assertTrue(tombstoned.deleted)
        }

        @Test
        fun `merging sessions with different subjects is refused`() {
            val first = stoppedSession(id = "session-1", subjectId = "subject-1")
            val second = stoppedSession(id = "session-2", subjectId = "subject-2", startedAt = first.endedAt!!)

            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.Merge(listOf(first.id, second.id)),
                    sessions = mapOf(first.id to first, second.id to second),
                    correctionId = "correction-1",
                    at = AT,
                )

            assertEquals(HistoryRejection.MERGE_SUBJECT_MISMATCH, result.assertRejected())
        }

        @Test
        fun `merging fewer than two sessions is refused`() {
            val session = stoppedSession()

            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.Merge(listOf(session.id)),
                    sessions = mapOf(session.id to session),
                    correctionId = "correction-1",
                    at = AT,
                )

            assertEquals(HistoryRejection.MERGE_REQUIRES_AT_LEAST_TWO_SESSIONS, result.assertRejected())
        }
    }

    @Nested
    @DisplayName("manual entry")
    inner class ManualEntry {
        @Test
        fun `a manual entry is recorded already stopped, with a real duration`() {
            val start = TEST_WALL_CLOCK
            val end = start + 45.minutes

            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.ManualEntry(
                        sessionId = "manual-1",
                        deviceId = "device-1",
                        subjectId = "subject-1",
                        note = "offline reading",
                        startedAt = start,
                        endedAt = end,
                    ),
                    sessions = emptyMap(),
                    correctionId = "correction-1",
                    at = AT,
                )

            val session = result.assertApplied().sessions.single()
            assertEquals(SessionStatus.STOPPED, session.status)
            assertEquals(45.minutes, session.elapsed.counted)
            assertTrue(session.manualOverride)
        }

        @Test
        fun `a manual entry that ends before it starts is refused`() {
            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.ManualEntry(
                        sessionId = "manual-1",
                        deviceId = "device-1",
                        subjectId = null,
                        note = null,
                        startedAt = TEST_WALL_CLOCK,
                        endedAt = TEST_WALL_CLOCK,
                    ),
                    sessions = emptyMap(),
                    correctionId = "correction-1",
                    at = AT,
                )

            assertEquals(HistoryRejection.INVALID_TIMING, result.assertRejected())
        }
    }

    @Nested
    @DisplayName("deleting and undoing")
    inner class DeletingAndUndoing {
        @Test
        fun `deleting tombstones a session rather than removing it`() {
            val session = stoppedSession()

            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.Delete(session.id),
                    sessions = mapOf(session.id to session),
                    correctionId = "correction-1",
                    at = AT,
                )

            assertTrue(
                result
                    .assertApplied()
                    .sessions
                    .single()
                    .deleted,
            )
        }

        @Test
        fun `deleting an already-deleted session is refused`() {
            val session = stoppedSession().copy(deleted = true)

            val result =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.Delete(session.id),
                    sessions = mapOf(session.id to session),
                    correctionId = "correction-1",
                    at = AT,
                )

            assertEquals(HistoryRejection.SESSION_DELETED, result.assertRejected())
        }

        @Test
        fun `undo restores exactly the state a deletion overwrote`() {
            val session = stoppedSession()
            val deletion =
                SessionHistoryEditor
                    .execute(
                        HistoryEditCommand.Delete(session.id),
                        sessions = mapOf(session.id to session),
                        correctionId = "correction-1",
                        at = AT,
                    ).assertApplied()
            val deleted = deletion.sessions.single()

            val undone =
                SessionHistoryEditor.execute(
                    HistoryEditCommand.Undo(deletion.correction),
                    sessions = mapOf(deleted.id to deleted),
                    correctionId = "correction-2",
                    at = AT + 1.minutes,
                )

            val applied = undone.assertApplied()
            assertEquals(session, applied.sessions.single().copy(updatedAt = session.updatedAt))
            assertEquals(SessionCorrectionType.RESTORE, applied.correction.type)
            assertEquals("correction-1", applied.correction.restoresCorrectionId)
        }

        @Test
        fun `undo of an edit restores the pre-edit timing and clears the override flag`() {
            val session = stoppedSession()
            val edit =
                SessionHistoryEditor
                    .execute(
                        HistoryEditCommand.EditTiming(
                            session.id,
                            session.startedAt + 5.minutes,
                            session.endedAt!! + 5.minutes,
                        ),
                        sessions = mapOf(session.id to session),
                        correctionId = "correction-1",
                        at = AT,
                    ).assertApplied()
            val edited = edit.sessions.single()

            val undone =
                SessionHistoryEditor
                    .execute(
                        HistoryEditCommand.Undo(edit.correction),
                        sessions = mapOf(edited.id to edited),
                        correctionId = "correction-2",
                        at = AT + 1.minutes,
                    ).assertApplied()

            val restored = undone.sessions.single()
            assertEquals(session.startedAt, restored.startedAt)
            assertEquals(session.endedAt, restored.endedAt)
            assertFalse(restored.manualOverride)
        }

        @Test
        fun `undo of a split re-tombstones the half that did not exist before it`() {
            val session = stoppedSession(duration = 60.minutes)
            val split =
                SessionHistoryEditor
                    .execute(
                        HistoryEditCommand.Split(session.id, session.startedAt + 20.minutes, "session-2"),
                        sessions = mapOf(session.id to session),
                        correctionId = "correction-1",
                        at = AT,
                    ).assertApplied()
            val byId = split.sessions.associateBy(StudySession::id)

            val undone =
                SessionHistoryEditor
                    .execute(
                        HistoryEditCommand.Undo(split.correction),
                        sessions = byId,
                        correctionId = "correction-2",
                        at = AT + 1.minutes,
                    ).assertApplied()

            val restoredFirst = undone.sessions.single { it.id == session.id }
            val restoredSecond = undone.sessions.single { it.id == "session-2" }
            assertEquals(session.endedAt, restoredFirst.endedAt)
            assertFalse(restoredFirst.manualOverride)
            assertTrue(restoredSecond.deleted, "the row split created must be tombstoned, not resurrected as absent")
        }
    }

    private fun HistoryEditResult.assertApplied(): HistoryEditResult.Applied {
        assertTrue(this is HistoryEditResult.Applied, "expected Applied but was $this")
        return this as HistoryEditResult.Applied
    }

    private fun HistoryEditResult.assertRejected(): HistoryRejection {
        assertTrue(this is HistoryEditResult.Rejected, "expected Rejected but was $this")
        return (this as HistoryEditResult.Rejected).reason
    }

    private companion object {
        val AT = TEST_WALL_CLOCK + 2.minutes

        fun stoppedSession(
            id: String = "session-1",
            subjectId: String? = "subject-1",
            startedAt: kotlin.time.Instant = TEST_WALL_CLOCK,
            duration: kotlin.time.Duration = 30.minutes,
        ): StudySession =
            testStudySession(
                id = id,
                subjectId = subjectId,
                startedAt = startedAt,
                endedAt = startedAt + duration,
                status = SessionStatus.STOPPED,
                elapsed = SessionElapsed(counted = duration),
                updatedAt = startedAt,
            )
    }
}
