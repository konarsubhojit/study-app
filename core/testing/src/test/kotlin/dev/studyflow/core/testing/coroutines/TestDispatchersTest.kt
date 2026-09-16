package dev.studyflow.core.testing.coroutines

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.time.Duration.Companion.hours

@DisplayName("coroutine test rules")
class TestDispatchersTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    @Test
    fun `work dispatched to any injected dispatcher runs on the test scheduler`() =
        runTest {
            val dispatchers = testDispatcherProvider()
            val order = mutableListOf<String>()

            launch(dispatchers.io) { order += "io" }
            launch(dispatchers.default) { order += "default" }
            // Nothing has run yet: a standard dispatcher queues, so ordering stays explicit.
            assertEquals(emptyList<String>(), order)

            testScheduler.advanceUntilIdle()

            assertEquals(listOf("io", "default"), order)
        }

    @Test
    fun `delays are skipped rather than slept through`() =
        runTest {
            val dispatchers = testDispatcherProvider()
            var finished = false

            launch(dispatchers.default) {
                delay(8.hours)
                finished = true
            }
            testScheduler.advanceUntilIdle()

            assertTrue(finished)
        }

    @Test
    fun `the main dispatcher is usable inside a test`() =
        runTest {
            var ranOnMain = false

            withContext(Dispatchers.Main) { ranOnMain = true }

            assertTrue(ranOnMain)
        }
}
