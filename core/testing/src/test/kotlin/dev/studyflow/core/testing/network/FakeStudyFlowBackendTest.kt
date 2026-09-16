package dev.studyflow.core.testing.network

import dev.studyflow.core.network.error.ApiError
import dev.studyflow.core.network.model.StudySessionDto
import dev.studyflow.core.network.model.SubjectDto
import dev.studyflow.core.network.model.TaskDto
import dev.studyflow.core.network.version.ClientVersion
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("FakeStudyFlowBackend")
class FakeStudyFlowBackendTest {
    @Test
    fun `a feature test reads seeded data through the real client`() =
        runTest {
            val backend = FakeStudyFlowBackend(subjects = listOf(SubjectDto(id = "s1", name = "Maths")))

            val result = backend.api().subjects()

            assertEquals(listOf("Maths"), result.valueOrNull()?.map { it.name })
            assertEquals(listOf("/v1/subjects"), backend.requestedPaths)
        }

    @Test
    fun `the subject filter documented for tasks is honoured`() =
        runTest {
            val backend =
                FakeStudyFlowBackend(
                    tasks =
                        listOf(
                            TaskDto(id = "t1", subjectId = "s1", title = "Read"),
                            TaskDto(id = "t2", subjectId = "s2", title = "Revise"),
                        ),
                )

            val result = backend.api().tasks(subjectId = "s2")

            assertEquals(listOf("t2"), result.valueOrNull()?.map { it.id })
        }

    @Test
    fun `an uploaded session is recorded so a test can assert on it`() =
        runTest {
            val backend = FakeStudyFlowBackend()
            val session = StudySessionDto("session-1", "s1", "2026-03-01T10:00:00Z", focusedSeconds = 1_500)

            val result = backend.api().uploadSession(session)

            assertEquals(session, result.valueOrNull())
            assertEquals(listOf(session), backend.uploadedSessions)
        }

    @Test
    fun `a failing backend surfaces a user-facing error, not a status code`() =
        runTest {
            val backend = FakeStudyFlowBackend(failWith = HttpStatusCode.ServiceUnavailable)

            val error = backend.api().subjects().errorOrNull()

            assertInstanceOf(ApiError.Server::class.java, error)
        }

    @Test
    fun `the force-upgrade path can be exercised without a server`() =
        runTest {
            val backend =
                FakeStudyFlowBackend(
                    clientVersion = ClientVersion(1, 0, 0),
                    minimumClientVersion = ClientVersion(2, 0, 0),
                )

            val error = backend.api().subjects().errorOrNull()

            assertInstanceOf(ApiError.UpgradeRequired::class.java, error)
        }
}
