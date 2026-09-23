package dev.studyflow.core.domain.lifecycle

import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK
import dev.studyflow.core.testing.data.testContentHash
import dev.studyflow.core.testing.data.testMaterial
import dev.studyflow.core.testing.data.testSessionEvent
import dev.studyflow.core.testing.data.testStudySession
import dev.studyflow.core.testing.data.testStudyTask
import dev.studyflow.core.testing.data.testSubject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

@DisplayName("Archive merge policy")
class ArchiveMergePolicyTest {
    @Test
    fun `a record with no local counterpart is inserted`() {
        assertEquals(MergeDecision.INSERT, ArchiveMergePolicy.decide(TEST_WALL_CLOCK, localUpdatedAt = null))
    }

    @Test
    fun `a newer archived record wins`() {
        assertEquals(
            MergeDecision.UPDATE,
            ArchiveMergePolicy.decide(TEST_WALL_CLOCK + 1.hours, localUpdatedAt = TEST_WALL_CLOCK),
        )
    }

    @Test
    fun `newer local work is never clobbered`() {
        assertEquals(
            MergeDecision.SKIP,
            ArchiveMergePolicy.decide(TEST_WALL_CLOCK, localUpdatedAt = TEST_WALL_CLOCK + 1.hours),
        )
    }

    @Test
    fun `re-importing an identical record changes nothing`() {
        assertEquals(MergeDecision.SKIP, ArchiveMergePolicy.decide(TEST_WALL_CLOCK, localUpdatedAt = TEST_WALL_CLOCK))
    }

    @Test
    fun `an existing subject keeps the name the user gave it locally`() {
        val local = testSubject(id = "subject-1", name = "Renamed locally")
        val imported = listOf(testSubject(id = "subject-1", name = "Mathematics").toArchived())

        val outcome = ArchiveMergePolicy.mergeSubjects(imported, listOf(local))

        assertEquals(emptyList<Any>(), outcome.inserted)
        assertEquals(1, outcome.skipped)
    }

    @Test
    fun `a session that was running when the export was taken is restored as a closed one`() {
        val running =
            SessionWithLog(
                session = testStudySession(status = SessionStatus.RUNNING, endedAt = null),
                events = listOf(testSessionEvent(id = "event-1", sequence = 0)),
            )

        val restored =
            ArchiveMergePolicy
                .mergeSessions(listOf(running.toArchived()), local = emptyList())
                .inserted
                .single()
                .session

        assertEquals(SessionStatus.STOPPED, restored.status)
        assertEquals(TEST_WALL_CLOCK, restored.endedAt)
        assertTrue(restored.manualOverride, "a restored session's timing is a decision, not a replay")
    }

    @Test
    fun `a session already stopped is restored untouched`() {
        val stopped =
            SessionWithLog(
                session =
                    testStudySession(
                        status = SessionStatus.STOPPED,
                        endedAt = TEST_WALL_CLOCK + 1.hours,
                    ),
                events = emptyList(),
            )

        val restored = ArchiveMergePolicy.mergeSessions(listOf(stopped.toArchived()), emptyList()).inserted.single()

        assertEquals(stopped, restored)
    }

    @Test
    fun `a task the archive edited more recently replaces the local copy`() {
        val local = testStudyTask(id = "task-1", title = "Old title", updatedAt = TEST_WALL_CLOCK)
        val imported = testStudyTask(id = "task-1", title = "New title", updatedAt = TEST_WALL_CLOCK + 1.hours)

        val outcome = ArchiveMergePolicy.mergeTasks(listOf(imported.toArchived()), listOf(local))

        assertEquals("New title", outcome.updated.single().title)
        assertEquals(emptyList<Any>(), outcome.inserted)
    }

    @Test
    fun `the same file imported under two ids is not catalogued twice`() {
        val local = testMaterial(id = "material-local", contentHash = testContentHash("shared"))
        val imported = testMaterial(id = "material-remote", contentHash = testContentHash("shared"))

        val action = ArchiveMergePolicy.planMaterials(listOf(imported.toArchived(null)), listOf(local)).single()

        assertEquals(MergeDecision.SKIP, action.decision)
    }

    @Test
    fun `a material this device has never seen is inserted with no local copy yet`() {
        val imported = testMaterial(id = "material-remote", contentHash = testContentHash("new"))

        val action =
            ArchiveMergePolicy
                .planMaterials(listOf(imported.toArchived("materials/material-remote-notes.pdf")), local = emptyList())
                .single()

        assertEquals(MergeDecision.INSERT, action.decision)
        assertNull(action.localPath)
    }

    @Test
    fun `a newer archived material updates the row but keeps the cached file already on disk`() {
        val local =
            testMaterial(id = "material-1", localUri = "/data/materials/material-1")
                .copy(updatedAt = TEST_WALL_CLOCK)
        val imported = testMaterial(id = "material-1").copy(updatedAt = TEST_WALL_CLOCK + 1.hours)

        val action = ArchiveMergePolicy.planMaterials(listOf(imported.toArchived(null)), listOf(local)).single()

        assertEquals(MergeDecision.UPDATE, action.decision)
        assertEquals("/data/materials/material-1", action.localPath)
    }
}
