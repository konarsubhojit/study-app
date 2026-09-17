package dev.studyflow.core.domain.materials

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ShareImportInbox")
class ShareImportInboxTest {
    @Test
    fun `a late subscriber still sees a batch offered before it started collecting`() =
        runTest {
            val inbox = ShareImportInbox()

            inbox.offer(listOf("content://shared/1"))

            assertEquals(listOf("content://shared/1"), inbox.pending.value)
        }

    @Test
    fun `consuming clears the batch so it is not handed off twice`() =
        runTest {
            val inbox = ShareImportInbox()
            inbox.offer(listOf("content://shared/1"))

            inbox.consume()

            assertTrue(inbox.pending.value.isEmpty())
        }

    @Test
    fun `offering an empty batch is a no-op`() =
        runTest {
            val inbox = ShareImportInbox()
            inbox.offer(listOf("content://shared/1"))

            inbox.offer(emptyList())

            assertEquals(listOf("content://shared/1"), inbox.pending.value)
        }

    @Test
    fun `a new batch replaces whatever was pending before it was consumed`() =
        runTest {
            val inbox = ShareImportInbox()
            inbox.offer(listOf("content://shared/1"))

            inbox.offer(listOf("content://shared/2", "content://shared/3"))

            assertEquals(listOf("content://shared/2", "content://shared/3"), inbox.pending.value)
        }
}
