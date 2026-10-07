package dev.studyflow.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.database.repository.RoomUploadProgressStore
import dev.studyflow.core.domain.materials.CompletedUploadPart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [DATABASE_ROBOLECTRIC_SDK])
class UploadPartSessionMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            StudyFlowDatabase::class.java,
        )

    @Test
    fun `migration 13 to 14 preserves legacy receipts and scoped receipts survive database reopen`() =
        runBlocking {
            seedLegacyReceipt()

            helper
                .runMigrationsAndValidate(
                    DATABASE_NAME,
                    StudyFlowDatabase.VERSION,
                    true,
                    *DatabaseMigrations.ALL,
                ).use { database ->
                    database
                        .query(
                            "SELECT part_number, etag, size_bytes, completed_at, upload_id FROM material_upload_parts",
                        ).use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            assertEquals(1, cursor.getInt(0))
                            assertEquals("\"legacy-etag\"", cursor.getString(1))
                            assertEquals(8_388_608L, cursor.getLong(2))
                            assertEquals(1_789_601_069_317L, cursor.getLong(3))
                            assertTrue(cursor.isNull(4))
                            assertFalse(cursor.moveToNext())
                        }
                }

            val clock = Clock { Instant.parse("2026-09-01T00:00:00Z") }
            val receipt = CompletedUploadPart(2, "\"new-etag\"", 8_388_608L, "new-session")
            val database = openDatabase()
            try {
                RoomUploadProgressStore(database.materialUploadPartDao(), clock)
                    .recordCompletedPart("legacy-material", receipt)
            } finally {
                database.close()
            }
            val reopened = openDatabase()
            try {
                val receipts =
                    RoomUploadProgressStore(reopened.materialUploadPartDao(), clock).completedParts("legacy-material")
                assertEquals(CompletedUploadPart(1, "\"legacy-etag\"", 8_388_608L), receipts.first())
                assertEquals(receipt, receipts.last())
                assertEquals(2, receipts.size)
            } finally {
                reopened.close()
            }
        }

    private fun seedLegacyReceipt() {
        helper.createDatabase(DATABASE_NAME, 13).use { database ->
            database.execSQL(
                """
                INSERT INTO materials (
                    id, display_name, mime_type, size_bytes, content_hash, created_at, updated_at,
                    pinned_for_offline, encrypted, deleted, preview_page_index, preview_position_millis,
                    playback_speed, device_id, sync_state
                ) VALUES (
                    'legacy-material', 'Legacy.pdf', 'application/pdf', 8388608, '${"3".repeat(64)}',
                    1789601069317, 1789601069317, 0, 0, 0, 0, 0, 1.0, 'legacy-device', 'UPLOADING'
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO material_upload_parts (material_id, part_number, etag, size_bytes, completed_at)
                VALUES ('legacy-material', 1, '"legacy-etag"', 8388608, 1789601069317)
                """.trimIndent(),
            )
        }
    }

    private fun openDatabase(): StudyFlowDatabase =
        Room
            .databaseBuilder(RuntimeEnvironment.getApplication(), StudyFlowDatabase::class.java, DATABASE_NAME)
            .addMigrations(*DatabaseMigrations.ALL)
            .build()

    private companion object {
        const val DATABASE_NAME = "upload-part-session-migration.db"
    }
}
