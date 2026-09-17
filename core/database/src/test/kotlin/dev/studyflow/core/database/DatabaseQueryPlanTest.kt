package dev.studyflow.core.database

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [DATABASE_ROBOLECTRIC_SDK])
class DatabaseQueryPlanTest {
    private lateinit var database: StudyFlowDatabase

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    StudyFlowDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `material folder query uses covering order index without temporary sort`() =
        runBlocking {
            SyntheticDataSeeder.seedIfEmpty(
                database,
                SyntheticDataFactory.create(SyntheticDataSize(8, 24, 5_000, 100, 20, 4)),
            )

            val plan =
                explain(
                    """
                    SELECT * FROM materials
                    WHERE deleted = 0 AND folder_id = 'folder-1'
                    ORDER BY updated_at DESC, id ASC
                    """.trimIndent(),
                )

            assertTrue(plan.any { it.contains("index_materials_folder_id_updated_at") })
            assertFalse(plan.any { it.contains("TEMP B-TREE") })
        }

    @Test
    fun `open task list queries use the covering due-instant index without temporary sort`() =
        runBlocking {
            SyntheticDataSeeder.seedIfEmpty(
                database,
                SyntheticDataFactory.create(SyntheticDataSize(8, 24, 100, 20_000, 20, 4)),
            )

            val windows =
                listOf(
                    "due_at_utc < 1772086400000",
                    "due_at_utc >= 1772000000000 AND due_at_utc < 1772086400000",
                    "due_at_utc >= 1772086400000",
                )

            windows.forEach { window ->
                val plan =
                    explain(
                        """
                        SELECT * FROM study_tasks
                        WHERE deleted = 0 AND completed_at IS NULL AND $window
                        ORDER BY due_at_utc ASC, id ASC
                        """.trimIndent(),
                    )

                assertTrue(
                    "$window should use the open-task index, plan was $plan",
                    plan.any { it.contains("index_study_tasks_open_due_at_utc") },
                )
                assertFalse("$window sorted in memory: $plan", plan.any { it.contains("TEMP B-TREE") })
                assertFalse("$window scanned the table: $plan", plan.any { it.contains("SCAN study_tasks") })
            }
        }

    @Test
    fun `tag filter resolves through the tag index rather than scanning tasks`() =
        runBlocking {
            SyntheticDataSeeder.seedIfEmpty(
                database,
                SyntheticDataFactory.create(SyntheticDataSize(8, 24, 100, 20_000, 20, 4)),
            )

            val plan =
                explain(
                    """
                    SELECT study_tasks.* FROM study_tasks
                    JOIN task_tags ON task_tags.task_id = study_tasks.id
                    WHERE task_tags.tag = 'exam' AND study_tasks.deleted = 0
                    """.trimIndent(),
                )

            assertTrue("plan was $plan", plan.any { it.contains("index_task_tags_tag") })
            assertFalse("plan was $plan", plan.any { it.contains("SCAN study_tasks") })
        }

    @Test
    fun `material tag filter resolves through the tag index rather than scanning materials`() =
        runBlocking {
            SyntheticDataSeeder.seedIfEmpty(
                database,
                SyntheticDataFactory.create(SyntheticDataSize(8, 24, 5_000, 100, 20, 4)),
            )

            val plan =
                explain(
                    """
                    SELECT materials.* FROM materials
                    JOIN material_tags ON material_tags.material_id = materials.id
                    WHERE material_tags.tag = 'exam' AND materials.deleted = 0
                    """.trimIndent(),
                )

            assertTrue("plan was $plan", plan.any { it.contains("index_material_tags_tag") })
            assertFalse("plan was $plan", plan.any { it.contains("SCAN materials") })
        }

    @Test
    fun `material full text search uses the virtual table index`() =
        runBlocking {
            SyntheticDataSeeder.seedIfEmpty(
                database,
                SyntheticDataFactory.create(SyntheticDataSize(8, 24, 5_000, 100, 20, 4)),
            )

            val plan =
                explain(
                    """
                    SELECT materials.* FROM materials
                    JOIN material_fts ON material_fts.material_id = materials.id
                    WHERE material_fts MATCH 'Synthetic'
                    """.trimIndent(),
                )

            assertTrue("plan was $plan", plan.any { it.contains("material_fts VIRTUAL TABLE INDEX") })
        }

    @Test
    fun `outstanding reminder scan uses the trigger index without temporary sort`() =
        runBlocking {
            SyntheticDataSeeder.seedIfEmpty(
                database,
                SyntheticDataFactory.create(SyntheticDataSize(8, 24, 100, 20_000, 20, 4)),
            )

            val plan =
                explain(
                    """
                    SELECT * FROM reminders
                    WHERE trigger_at_utc IS NOT NULL AND trigger_at_utc <= 1772086400000
                    ORDER BY trigger_at_utc ASC, id ASC
                    """.trimIndent(),
                )

            assertTrue("plan was $plan", plan.any { it.contains("index_reminders_trigger_at_utc") })
            assertFalse("plan was $plan", plan.any { it.contains("TEMP B-TREE") })
        }

    @Test
    fun `session event fold query uses unique sequence index without temporary sort`() =
        runBlocking {
            SyntheticDataSeeder.seedIfEmpty(
                database,
                SyntheticDataFactory.create(SyntheticDataSize(4, 4, 20, 20, 100, 20)),
            )

            val plan =
                explain(
                    """
                    SELECT * FROM session_events
                    WHERE session_id = 'session-50'
                    ORDER BY sequence ASC
                    """.trimIndent(),
                )

            assertTrue(plan.any { it.contains("index_session_events_session_id_sequence") })
            assertFalse(plan.any { it.contains("TEMP B-TREE") })
        }

    @Test
    fun `active session lookup uses the device index`() =
        runBlocking {
            SyntheticDataSeeder.seedIfEmpty(
                database,
                SyntheticDataFactory.create(SyntheticDataSize(4, 4, 20, 20, 100, 20)),
            )

            val plan =
                explain(
                    """
                    SELECT id FROM study_sessions
                    WHERE device_id = 'synthetic-device' AND deleted = 0 AND status != 'STOPPED'
                    """.trimIndent(),
                )

            assertTrue(plan.any { it.contains("index_study_sessions_device_id_status_deleted") })
        }

    private fun explain(sql: String): List<String> =
        database.query("EXPLAIN QUERY PLAN $sql", emptyArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(3))
                }
            }
        }
}
