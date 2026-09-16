package dev.studyflow.core.testing.quarantine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@DisplayName("flaky quarantine")
class FlakyTest {
    @Test
    fun `the annotation carries the tag the build filters on`() {
        // The build excludes `flaky` from `./gradlew test` and the slow suite runs only it, so the
        // tag on this annotation is the contract between the marker and both test tasks.
        val tag = Flaky::class.java.getAnnotation(Tag::class.java)

        assertEquals(FLAKY_TAG, tag.value)
    }

    @Test
    fun `quarantining records where the flake is tracked`() {
        val annotation = Quarantined::class.java.getAnnotation(Flaky::class.java)

        assertEquals("#65", annotation.issue)
    }

    @Flaky(issue = "#65", reason = "example for the annotation's own test")
    private class Quarantined
}
