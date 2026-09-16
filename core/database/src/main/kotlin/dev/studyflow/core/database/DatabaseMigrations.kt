package dev.studyflow.core.database

import androidx.room.migration.Migration
import androidx.sqlite.execSQL

/** Complete ordered migration registry for every schema version shipped by StudyFlow. */
public object DatabaseMigrations {
    public val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
            override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
                connection.execSQL("ALTER TABLE materials ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("DROP INDEX index_subjects_archived_name")
                connection.execSQL(
                    "CREATE INDEX index_subjects_archived_name ON subjects (archived, name, id)",
                )
                connection.execSQL("DROP INDEX index_folders_parent_id_name")
                connection.execSQL(
                    "CREATE INDEX index_folders_parent_id_name ON folders (parent_id, name, id)",
                )
                connection.execSQL("DROP INDEX index_materials_folder_id_created_at")
                connection.execSQL(
                    """
                    CREATE INDEX index_materials_folder_id_created_at
                    ON materials (folder_id, created_at DESC, id ASC)
                    """.trimIndent(),
                )
                connection.execSQL("DROP INDEX index_materials_sync_state_created_at")
                connection.execSQL(
                    "CREATE INDEX index_materials_sync_state_created_at ON materials (sync_state, created_at, id)",
                )
                connection.execSQL(
                    "CREATE INDEX index_materials_created_at ON materials (created_at DESC, id ASC)",
                )
                connection.execSQL("DROP INDEX index_study_tasks_subject_id_due_at")
                connection.execSQL(
                    "CREATE INDEX index_study_tasks_subject_id_due_at ON study_tasks (subject_id, due_at, id)",
                )
                connection.execSQL("DROP INDEX index_study_tasks_completed_at_due_at")
                connection.execSQL(
                    "CREATE INDEX index_study_tasks_completed_at_due_at ON study_tasks (completed_at, due_at, id)",
                )
            }
        }

    public val ALL: Array<Migration>
        get() = arrayOf(MIGRATION_1_2)
}
