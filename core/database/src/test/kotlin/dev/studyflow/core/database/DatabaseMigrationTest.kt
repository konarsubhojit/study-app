package dev.studyflow.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `migration 3 to 4 resolves due instants, keeps reminders and moves the recurrence rule`() {
        helper.createDatabase(DATABASE_NAME, 3).use { database ->
            database.insertVersionThreeTaskWithReminder()
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
    fun `migration 2 to 3 derives the session projection from the event log`() {
        helper.createDatabase(DATABASE_NAME, 2).use { database -> database.insertVersionTwoSession() }

        helper
            .runMigrationsAndValidate(
                DATABASE_NAME,
                StudyFlowDatabase.VERSION,
                true,
                *DatabaseMigrations.ALL,
            ).use { database ->
                database
                    .query(
                        "SELECT status, started_at, ended_at, device_id, updated_at, deleted " +
                            "FROM study_sessions WHERE id = 'legacy-session'",
                    ).use { cursor ->
                        assertEquals(true, cursor.moveToFirst())
                        assertEquals("STOPPED", cursor.getString(0))
                        assertEquals(1_000L, cursor.getLong(1))
                        assertEquals(3_000L, cursor.getLong(2))
                        assertEquals(DatabaseMigrations.MIGRATED_DEVICE_ID, cursor.getString(3))
                        assertEquals(3_000L, cursor.getLong(4))
                        assertEquals(0, cursor.getInt(5))
                    }
                // Rebuilding the table must not take the log with it; the log is the only truth.
                database.query("SELECT COUNT(*) FROM session_events").use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(2, cursor.getInt(0))
                }
                // A session with no events was never started, so it is dropped rather than given
                // an epoch-dated projection that would read as active forever.
                database.query("SELECT COUNT(*) FROM study_sessions").use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            }
    }

    @Test
    fun `migration 4 to 5 keeps existing rules and leaves the new grammar unset`() {
        helper.createDatabase(DATABASE_NAME, 4).use { database ->
            database.insertVersionFourRecurringTask()
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
                        SELECT recurrence_frequency, recurrence_days_of_week, recurrence_week_of_month,
                        recurrence_month_of_year, recurrence_exceptions FROM study_tasks
                        """.trimIndent(),
                    ).use { cursor ->
                        assertEquals(true, cursor.moveToFirst())
                        assertEquals("WEEKLY", cursor.getString(0))
                        assertEquals("MONDAY,WEDNESDAY", cursor.getString(1))
                        assertTrue("a pre-existing rule counts no weekday", cursor.isNull(2))
                        assertTrue("a weekly rule names no month", cursor.isNull(3))
                        assertTrue("nothing has been excluded from the series yet", cursor.isNull(4))
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

    private fun SupportSQLiteDatabase.insertVersionThreeTaskWithReminder() {
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

    private fun SupportSQLiteDatabase.insertVersionFourRecurringTask() {
        execSQL(
            """
            INSERT INTO study_tasks (
                id, title, notes, subject_id, material_id, session_id, due_at, due_at_utc,
                time_zone, is_all_day, priority, recurrence_frequency, recurrence_interval,
                recurrence_days_of_week, recurrence_day_of_month, recurrence_end_type,
                recurrence_end_count, recurrence_end_date, completed_at, updated_at, deleted
            ) VALUES (
                'weekly-task', 'Revise statistics', NULL, NULL, NULL, NULL, '2026-03-02T08:00',
                1772438400000, 'Europe/London', 0, 'NORMAL', 'WEEKLY', 1, 'MONDAY,WEDNESDAY',
                NULL, 'NEVER', NULL, NULL, NULL, 1772438400000, 0
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.insertVersionTwoSession() {
        execSQL("INSERT INTO study_sessions (id, subject_id, note) VALUES ('legacy-session', NULL, 'Algebra')")
        execSQL("INSERT INTO study_sessions (id, subject_id, note) VALUES ('never-started', NULL, NULL)")
        execSQL(
            """
            INSERT INTO session_events (id, session_id, type, uptime, wall_clock, boot_id, sequence)
            VALUES ('legacy-start', 'legacy-session', 'STARTED', 0, 1000, 'boot-legacy', 0)
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO session_events (id, session_id, type, uptime, wall_clock, boot_id, sequence)
            VALUES ('legacy-stop', 'legacy-session', 'STOPPED', 2000, 3000, 'boot-legacy', 1)
            """.trimIndent(),
        )
    }

    private companion object {
        const val DATABASE_NAME = "migration-test"
    }
}
