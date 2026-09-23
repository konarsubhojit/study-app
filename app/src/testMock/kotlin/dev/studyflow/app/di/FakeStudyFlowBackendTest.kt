package dev.studyflow.app.di

import dev.studyflow.core.network.ApiResult
import dev.studyflow.core.network.model.StudySessionDto
import dev.studyflow.core.network.model.SyncPushRequestDto
import dev.studyflow.core.network.model.SyncSessionDto
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FakeStudyFlowBackendTest {
    @Test
    fun `seeded data is available without a server`() =
        runTest {
            val backend = FakeStudyFlowBackend()

            val subjects = backend.subjects()
            val mathsTasks = backend.tasks("maths")

            assertTrue(subjects is ApiResult.Success)
            assertEquals(2, (subjects as ApiResult.Success).value.size)
            assertEquals(listOf("Practice algebra"), (mathsTasks as ApiResult.Success).value.map { it.title })
        }

    @Test
    fun `sync operations complete entirely in process`() =
        runTest {
            val backend = FakeStudyFlowBackend()
            val uploaded =
                StudySessionDto(
                    id = "session-1",
                    subjectId = "maths",
                    startedAtIso = "2026-09-23T00:00:00Z",
                    focusedSeconds = 600,
                )
            val change =
                SyncSessionDto(
                    id = "session-1",
                    deviceId = "demo",
                    updatedAtIso = "2026-09-23T00:10:00Z",
                    startedAtIso = "2026-09-23T00:00:00Z",
                    status = "STOPPED",
                )

            assertEquals(uploaded, (backend.uploadSession(uploaded) as ApiResult.Success).value)
            assertEquals(
                listOf("session-1"),
                (
                    backend.pushSessionChanges(SyncPushRequestDto("demo", listOf(change))) as
                        ApiResult.Success
                ).value.acceptedIds,
            )
            assertEquals(
                emptyList<SyncSessionDto>(),
                (backend.sessionChanges(cursor = "ignored", limit = 1) as ApiResult.Success).value.changes,
            )
        }
}
