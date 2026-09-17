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

    /**
     * Grows `study_sessions` from a bare id/subject/note row into the session projection.
     *
     * The new timing columns are *derived* rather than defaulted: every value is recomputed from
     * the append-only `session_events` log, which is the same reduction the app performs at
     * runtime. That is the whole point of event sourcing — a schema change cannot lose a
     * measurement, because the measurement was never stored in the first place.
     *
     * Legacy rows predate the device column and are attributed to [MIGRATED_DEVICE_ID]: the log
     * does not record which device wrote it, and inventing the current device would let a session
     * from another phone contend for the "one active session" slot.
     */
    public val MIGRATION_2_3: Migration =
        object : Migration(2, 3) {
            override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
                connection.execSQL(CREATE_SESSION_PROJECTION)
                connection.execSQL(PROJECT_SESSIONS_FROM_EVENTS)
                connection.execSQL("DROP TABLE study_sessions")
                connection.execSQL("ALTER TABLE study_sessions_new RENAME TO study_sessions")
                connection.execSQL(
                    "CREATE INDEX index_study_sessions_subject_id ON study_sessions (subject_id)",
                )
                connection.execSQL(
                    """
                    CREATE INDEX index_study_sessions_device_id_status_deleted
                    ON study_sessions (device_id, status, deleted)
                    """.trimIndent(),
                )
            }
        }

    /** Device attributed to sessions written before the projection recorded one. */
    public const val MIGRATED_DEVICE_ID: String = "migrated-device"

    public val ALL: Array<Migration>
        get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

    private val CREATE_SESSION_PROJECTION =
        """
        CREATE TABLE study_sessions_new (
            `id` TEXT NOT NULL,
            `subject_id` TEXT,
            `note` TEXT,
            `status` TEXT NOT NULL,
            `started_at` INTEGER NOT NULL,
            `ended_at` INTEGER,
            `device_id` TEXT NOT NULL,
            `updated_at` INTEGER NOT NULL,
            `deleted` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`subject_id`) REFERENCES `subjects`(`id`)
                ON UPDATE NO ACTION ON DELETE SET NULL
        )
        """.trimIndent()

    private val PROJECT_SESSIONS_FROM_EVENTS =
        """
        INSERT INTO study_sessions_new (
            id, subject_id, note, status, started_at, ended_at, device_id, updated_at, deleted
        )
        SELECT
            s.id,
            s.subject_id,
            s.note,
            CASE
                WHEN EXISTS (
                    SELECT 1 FROM session_events e
                    WHERE e.session_id = s.id AND e.type = 'STOPPED'
                ) THEN 'STOPPED'
                WHEN (
                    SELECT e.type FROM session_events e
                    WHERE e.session_id = s.id
                    ORDER BY e.sequence DESC LIMIT 1
                ) IN ('STARTED', 'RESUMED') THEN 'RUNNING'
                ELSE 'PAUSED'
            END,
            COALESCE((SELECT MIN(e.wall_clock) FROM session_events e WHERE e.session_id = s.id), 0),
            (
                SELECT MAX(e.wall_clock) FROM session_events e
                WHERE e.session_id = s.id AND e.type = 'STOPPED'
            ),
            '$MIGRATED_DEVICE_ID',
            COALESCE((SELECT MAX(e.wall_clock) FROM session_events e WHERE e.session_id = s.id), 0),
            0
        FROM study_sessions s
        """.trimIndent()
}
