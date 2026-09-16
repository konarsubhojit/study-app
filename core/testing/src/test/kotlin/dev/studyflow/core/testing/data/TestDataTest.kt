package dev.studyflow.core.testing.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("data builders")
class TestDataTest {
    @Test
    fun `defaults produce valid models`() {
        // Every model validates itself in `init`, so constructing one is the assertion: a builder
        // whose defaults were invalid would fail here rather than in someone else's test.
        assertEquals("subject-1", testSubject().id)
        assertEquals("task-1", testStudyTask().id)
        assertEquals(TEST_WALL_CLOCK, testStudySession().startedAt)
        assertEquals(TEST_BOOT_ID, testSessionEvent().anchor.bootId)
        assertEquals("lecture-notes.pdf", testMaterial().displayName)
    }

    @Test
    fun `named arguments override only what a test cares about`() {
        val material = testMaterial(displayName = "past-paper.pdf", pinnedForOffline = true)

        assertEquals("past-paper.pdf", material.displayName)
        assertEquals(true, material.pinnedForOffline)
        assertEquals(testMaterial().mimeType, material.mimeType)
    }

    @Test
    fun `content hashes are valid, stable and seed-dependent`() {
        val hash = testContentHash("notes")

        assertEquals(SHA256_HEX_LENGTH, hash.hex.length)
        assertEquals(hash, testContentHash("notes"))
        assertNotEquals(hash, testContentHash("slides"))
    }
}
