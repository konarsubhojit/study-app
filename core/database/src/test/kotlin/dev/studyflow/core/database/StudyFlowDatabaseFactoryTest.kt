package dev.studyflow.core.database

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StudyFlowDatabaseFactoryTest {
    @Test
    fun `non-debuggable application cannot enable destructive migrations`() {
        assertThrows(IllegalStateException::class.java) {
            StudyFlowDatabaseFactory.enforceMigrationPolicy(
                isApplicationDebuggable = false,
                policy = MissingMigrationPolicy.DESTRUCTIVE_FOR_DEVELOPMENT,
            )
        }
    }

    @Test
    fun `strict migrations are valid in every build and destructive fallback is debug only`() {
        assertDoesNotThrow {
            StudyFlowDatabaseFactory.enforceMigrationPolicy(false, MissingMigrationPolicy.FAIL)
            StudyFlowDatabaseFactory.enforceMigrationPolicy(
                true,
                MissingMigrationPolicy.DESTRUCTIVE_FOR_DEVELOPMENT,
            )
        }
    }
}
