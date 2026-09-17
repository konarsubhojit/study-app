package dev.studyflow.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [DATABASE_ROBOLECTRIC_SDK])
class DatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            StudyFlowDatabase::class.java,
        )

    @Test
    fun `migration 1 to 2 preserves materials and defaults encryption to false`() {
        helper.createDatabase(DATABASE_NAME, 1).use { database ->
            database.insertVersionOneMaterial()
        }

        helper
            .runMigrationsAndValidate(
                DATABASE_NAME,
                StudyFlowDatabase.VERSION,
                true,
                *DatabaseMigrations.ALL,
            ).use { database ->
                database.query("SELECT id, encrypted FROM materials").use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals("legacy-material", cursor.getString(0))
                    assertEquals(0, cursor.getInt(1))
                    assertFalse(cursor.moveToNext())
                }
            }
    }

    @Test
    fun `migration registry covers every shipped version`() {
        val paths = DatabaseMigrations.ALL.map { it.startVersion to it.endVersion }
        val expected = (1 until StudyFlowDatabase.VERSION).map { it to it + 1 }

        assertEquals(expected, paths)
    }

    private fun SupportSQLiteDatabase.insertVersionOneMaterial() {
        execSQL(
            """
            INSERT INTO materials (
                id, folder_id, display_name, mime_type, size_bytes, content_hash, created_at,
                sync_state, uploaded_bytes, upload_total_bytes, failure_reason, failure_retryable,
                local_uri, pinned_for_offline
            ) VALUES (
                'legacy-material', NULL, 'Legacy.pdf', 'application/pdf', 1024,
                '${"0".repeat(64)}', 1789601069317, 'PENDING', NULL, NULL, NULL, NULL, NULL, 0
            )
            """.trimIndent(),
        )
    }

    private companion object {
        const val DATABASE_NAME = "migration-test"
    }
}
