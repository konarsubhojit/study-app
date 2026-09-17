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
    fun `migration 2 to 3 resolves due instants, keeps reminders and moves the recurrence rule`() {
        helper.createDatabase(DATABASE_NAME, 2).use { database ->
            database.insertVersionTwoTaskWithReminder()
        }

        helper
            .runMigrationsAndValidate(
                DATABASE_NAME,
                StudyFlowDatabase.VERSION,
                true,
                *DatabaseMigrations.ALL,
            ).use { database ->
                database
                    .query(
                        """
                        SELECT due_at, due_at_utc, is_all_day, priority, recurrence_frequency, deleted
                        FROM study_tasks
                        """.trimIndent(),
                    ).use { cursor ->
                        assertEquals(true, cursor.moveToFirst())
                        assertEquals("2026-10-25T09:00", cursor.getString(0))
                        // 09:00 in London on the day the clocks go back is 09:00 GMT, not 08:00 UTC:
                        // only a zone-aware conversion gets this right.
                        assertEquals(1_792_918_800_000L, cursor.getLong(1))
                        assertEquals(0, cursor.getInt(2))
                        assertEquals("NORMAL", cursor.getString(3))
                        assertEquals("WEEKLY", cursor.getString(4))
                        assertEquals(0, cursor.getInt(5))
                    }

                database
                    .query("SELECT id, trigger_type, lead_time, trigger_at_utc FROM reminders")
                    .use { cursor ->
                        assertEquals(true, cursor.moveToFirst())
                        assertEquals("legacy-reminder", cursor.getString(0))
                        assertEquals("BEFORE_DUE", cursor.getString(1))
                        assertEquals(1_800_000L, cursor.getLong(2))
                        assertEquals(1_792_917_000_000L, cursor.getLong(3))
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

    private fun SupportSQLiteDatabase.insertVersionTwoTaskWithReminder() {
        execSQL(
            """
            INSERT INTO study_tasks (id, title, notes, subject_id, due_at, time_zone, completed_at)
            VALUES ('legacy-task', 'Revise databases', NULL, NULL, '2026-10-25T09:00',
            'Europe/London', NULL)
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO reminders (
                id, task_id, lead_time, precision, recurrence_frequency, recurrence_interval,
                recurrence_days_of_week, recurrence_day_of_month, recurrence_end_type,
                recurrence_end_count, recurrence_end_date
            ) VALUES (
                'legacy-reminder', 'legacy-task', 1800000, 'EXACT', 'WEEKLY', 1, NULL, NULL,
                'NEVER', NULL, NULL
            )
            """.trimIndent(),
        )
    }

    private companion object {
        const val DATABASE_NAME = "migration-test"
    }
}
