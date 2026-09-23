package dev.studyflow.app.di

import dev.studyflow.core.network.ApiResult
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
}
