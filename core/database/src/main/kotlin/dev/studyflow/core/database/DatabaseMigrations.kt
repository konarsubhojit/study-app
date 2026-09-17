package dev.studyflow.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

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
     * The new timing columns are derived from the append-only `session_events` log, the same
     * reduction the app performs at runtime. Rows with no events are dropped because they were
     * never started, and legacy rows use [MIGRATED_DEVICE_ID] rather than claiming the current
     * device.
     */
    public val MIGRATION_2_3: Migration =
        object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(CREATE_SESSION_PROJECTION)
                connection.execSQL(PROJECT_SESSIONS_FROM_EVENTS)
                connection.execSQL("DROP TABLE study_sessions")
                connection.execSQL("ALTER TABLE study_sessions_new RENAME TO study_sessions")
                connection.execSQL("CREATE INDEX index_study_sessions_subject_id ON study_sessions (subject_id)")
                connection.execSQL(
                    "CREATE INDEX index_study_sessions_device_id_status_deleted " +
                        "ON study_sessions (device_id, status, deleted)",
                )
            }
        }

    /** Device attributed to sessions written before the projection recorded one. */
    public const val MIGRATED_DEVICE_ID: String = "migrated-device"

    /**
     * Version 4 grows the task model: all-day and priority metadata, tags, a checklist, links to a
     * material and a study session, soft deletion, sync timestamps, and any number of reminders per
     * task with either a lead time or an absolute trigger.
     *
     * Both tables are rebuilt rather than altered, because the new columns include foreign keys and
     * the recurrence rule moves from the reminder to the task it repeats with. The `due_at_utc` and
     * `trigger_at_utc` columns the list screens are indexed on are derived here: the conversion
     * needs the IANA time-zone rules for each row's own date, which SQLite has no access to, so the
     * existing local due times are resolved in Kotlin.
     */
    public val MIGRATION_3_4: Migration =
        object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.rebuildTaskTables()
                connection.createTaskIndices()
                connection.createTaskChildTables()
                connection.backfillDueInstants()
                connection.backfillReminderTriggers()
            }
        }

    /** Version 5 completes the offline material catalog metadata, tag join table and FTS index. */
    public val MIGRATION_4_5: Migration =
        object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.rebuildMaterialTables()
            }
        }

    public val ALL: Array<Migration>
        get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

    // The task tables are rebuilt rather than altered: version 4 adds foreign keys and non-null
    // columns that SQLite cannot add in place, and Room validates the resulting DDL exactly.
    private fun SQLiteConnection.rebuildTaskTables() {
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `study_tasks_new` (
            `id` TEXT NOT NULL, `title` TEXT NOT NULL, `notes` TEXT, `subject_id` TEXT,
            `material_id` TEXT, `session_id` TEXT, `due_at` TEXT, `due_at_utc` INTEGER,
            `time_zone` TEXT NOT NULL, `is_all_day` INTEGER NOT NULL, `priority` TEXT NOT NULL,
            `recurrence_frequency` TEXT, `recurrence_interval` INTEGER,
            `recurrence_days_of_week` TEXT, `recurrence_day_of_month` INTEGER,
            `recurrence_end_type` TEXT, `recurrence_end_count` INTEGER, `recurrence_end_date` TEXT,
            `completed_at` INTEGER, `updated_at` INTEGER NOT NULL, `deleted` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`subject_id`) REFERENCES `subjects`(`id`)
            ON UPDATE NO ACTION ON DELETE SET NULL ,
            FOREIGN KEY(`material_id`) REFERENCES `materials`(`id`)
            ON UPDATE NO ACTION ON DELETE SET NULL ,
            FOREIGN KEY(`session_id`) REFERENCES `study_sessions`(`id`)
            ON UPDATE NO ACTION ON DELETE SET NULL )
            """.trimIndent(),
        )
        // The recurrence rule is inherited from the task's single version-2 reminder.
        execSQL(
            """
            INSERT INTO study_tasks_new (
            id, title, notes, subject_id, material_id, session_id, due_at, due_at_utc, time_zone,
            is_all_day, priority, recurrence_frequency, recurrence_interval, recurrence_days_of_week,
            recurrence_day_of_month, recurrence_end_type, recurrence_end_count, recurrence_end_date,
            completed_at, updated_at, deleted)
            SELECT t.id, t.title, t.notes, t.subject_id, NULL, NULL, t.due_at, NULL, t.time_zone,
            0, 'NORMAL', r.recurrence_frequency, r.recurrence_interval, r.recurrence_days_of_week,
            r.recurrence_day_of_month, r.recurrence_end_type, r.recurrence_end_count,
            r.recurrence_end_date, t.completed_at, CAST(strftime('%s', 'now') AS INTEGER) * 1000, 0
            FROM study_tasks AS t
            LEFT JOIN reminders AS r ON r.task_id = t.id
            """.trimIndent(),
        )
        rebuildReminders()
        execSQL("DROP TABLE reminders")
        execSQL("DROP TABLE study_tasks")
        execSQL("ALTER TABLE study_tasks_new RENAME TO study_tasks")
        execSQL("ALTER TABLE reminders_new RENAME TO reminders")
    }

    private fun SQLiteConnection.rebuildReminders() {
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `reminders_new` (
            `id` TEXT NOT NULL, `task_id` TEXT NOT NULL, `trigger_type` TEXT NOT NULL,
            `lead_time` INTEGER, `trigger_instant` INTEGER, `trigger_time_zone` TEXT,
            `trigger_at_utc` INTEGER, `precision` TEXT NOT NULL, `snooze_until` INTEGER,
            `snooze_count` INTEGER, `last_fired_at` INTEGER, `scheduling_id` TEXT,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`task_id`) REFERENCES `study_tasks`(`id`)
            ON UPDATE NO ACTION ON DELETE CASCADE )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO reminders_new (
            id, task_id, trigger_type, lead_time, trigger_instant, trigger_time_zone,
            trigger_at_utc, precision, snooze_until, snooze_count, last_fired_at, scheduling_id)
            SELECT id, task_id, 'BEFORE_DUE', lead_time, NULL, NULL, NULL, precision,
            NULL, NULL, NULL, NULL
            FROM reminders
            """.trimIndent(),
        )
    }

    private fun SQLiteConnection.createTaskIndices() {
        execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_study_tasks_open_due_at_utc
            ON study_tasks (deleted, completed_at, due_at_utc, id)
            """.trimIndent(),
        )
        execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_study_tasks_subject_id_due_at
            ON study_tasks (subject_id, due_at_utc, id)
            """.trimIndent(),
        )
        execSQL("CREATE INDEX IF NOT EXISTS index_study_tasks_material_id ON study_tasks (material_id)")
        execSQL("CREATE INDEX IF NOT EXISTS index_study_tasks_session_id ON study_tasks (session_id)")
        execSQL("CREATE INDEX IF NOT EXISTS index_study_tasks_updated_at ON study_tasks (updated_at, id)")
        execSQL("CREATE INDEX IF NOT EXISTS index_reminders_task_id ON reminders (task_id)")
        execSQL("CREATE INDEX IF NOT EXISTS index_reminders_trigger_at_utc ON reminders (trigger_at_utc, id)")
    }

    private fun SQLiteConnection.createTaskChildTables() {
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `task_tags` (
            `task_id` TEXT NOT NULL, `tag` TEXT NOT NULL, PRIMARY KEY(`task_id`, `tag`),
            FOREIGN KEY(`task_id`) REFERENCES `study_tasks`(`id`)
            ON UPDATE NO ACTION ON DELETE CASCADE )
            """.trimIndent(),
        )
        execSQL("CREATE INDEX IF NOT EXISTS index_task_tags_tag ON task_tags (tag, task_id)")
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `task_subtasks` (
            `id` TEXT NOT NULL, `task_id` TEXT NOT NULL, `position` INTEGER NOT NULL,
            `title` TEXT NOT NULL, `completed_at` INTEGER, PRIMARY KEY(`id`),
            FOREIGN KEY(`task_id`) REFERENCES `study_tasks`(`id`)
            ON UPDATE NO ACTION ON DELETE CASCADE )
            """.trimIndent(),
        )
        execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_task_subtasks_task_id_position
            ON task_subtasks (task_id, position)
            """.trimIndent(),
        )
    }

    private fun SQLiteConnection.rebuildMaterialTables() {
        // Dropping the old materials table can clear study_tasks.material_id through its SET NULL
        // foreign key, so keep the references and restore them after the replacement table exists.
        execSQL(
            """
            CREATE TEMP TABLE material_task_refs AS
            SELECT id, material_id FROM study_tasks WHERE material_id IS NOT NULL
            """.trimIndent(),
        )
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `materials_new` (
            `id` TEXT NOT NULL, `folder_id` TEXT, `subject_id` TEXT, `display_name` TEXT NOT NULL,
            `mime_type` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `content_hash` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `notes` TEXT,
            `remote_key` TEXT, `sync_state` TEXT NOT NULL, `uploaded_bytes` INTEGER,
            `upload_total_bytes` INTEGER, `failure_reason` TEXT, `failure_retryable` INTEGER,
            `local_path` TEXT, `pinned_for_offline` INTEGER NOT NULL, `encrypted` INTEGER NOT NULL,
            `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`),
            FOREIGN KEY(`folder_id`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL ,
            FOREIGN KEY(`subject_id`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO materials_new (
            id, folder_id, subject_id, display_name, mime_type, size_bytes, content_hash, created_at,
            updated_at, notes, remote_key, sync_state, uploaded_bytes, upload_total_bytes,
            failure_reason, failure_retryable, local_path, pinned_for_offline, encrypted, deleted)
            SELECT id, folder_id, NULL, display_name, mime_type, size_bytes, content_hash, created_at,
            created_at, NULL, NULL, sync_state, uploaded_bytes, upload_total_bytes, failure_reason,
            failure_retryable, local_uri, pinned_for_offline, encrypted, 0
            FROM materials
            """.trimIndent(),
        )
        execSQL("DROP TABLE materials")
        execSQL("ALTER TABLE materials_new RENAME TO materials")
        execSQL(
            """
            UPDATE study_tasks
            SET material_id = (SELECT material_id FROM material_task_refs WHERE material_task_refs.id = study_tasks.id)
            WHERE id IN (SELECT id FROM material_task_refs)
            """.trimIndent(),
        )
        execSQL("DROP TABLE material_task_refs")
        createMaterialCatalogIndices()
        execSQL("CREATE TABLE IF NOT EXISTS `tags` (`name` TEXT NOT NULL, PRIMARY KEY(`name`))")
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `material_tags` (
            `material_id` TEXT NOT NULL, `tag` TEXT NOT NULL, PRIMARY KEY(`material_id`, `tag`),
            FOREIGN KEY(`material_id`) REFERENCES `materials`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE ,
            FOREIGN KEY(`tag`) REFERENCES `tags`(`name`) ON UPDATE NO ACTION ON DELETE CASCADE )
            """.trimIndent(),
        )
        execSQL("CREATE INDEX IF NOT EXISTS index_material_tags_tag ON material_tags (tag, material_id)")
        execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS `material_fts`
            USING FTS4(`material_id` TEXT NOT NULL, `display_name` TEXT NOT NULL, `notes` TEXT)
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO material_fts(material_id, display_name, notes)
            SELECT id, display_name, notes FROM materials WHERE deleted = 0
            """.trimIndent(),
        )
    }

    private fun SQLiteConnection.createMaterialCatalogIndices() {
        execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_materials_folder_id_updated_at
            ON materials (deleted ASC, folder_id ASC, updated_at DESC, id ASC)
            """.trimIndent(),
        )
        execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_materials_subject_id_updated_at
            ON materials (deleted, subject_id, updated_at, id)
            """.trimIndent(),
        )
        execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_materials_mime_type_updated_at
            ON materials (deleted, mime_type, updated_at, id)
            """.trimIndent(),
        )
        execSQL("CREATE INDEX IF NOT EXISTS index_materials_folder_id ON materials (folder_id)")
        execSQL("CREATE INDEX IF NOT EXISTS index_materials_subject_id ON materials (subject_id)")
        execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_materials_sync_state_updated_at
            ON materials (deleted, sync_state, updated_at, id)
            """.trimIndent(),
        )
        execSQL("CREATE INDEX IF NOT EXISTS index_materials_content_hash ON materials (content_hash)")
        execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_materials_updated_at
            ON materials (deleted ASC, updated_at DESC, id ASC)
            """.trimIndent(),
        )
        execSQL("CREATE INDEX IF NOT EXISTS index_materials_size_bytes ON materials (deleted, size_bytes, id)")
        execSQL("CREATE INDEX IF NOT EXISTS index_materials_display_name ON materials (deleted, display_name, id)")
    }

    /** Resolves each preserved local due time against its own zone, which only Kotlin can do. */
    private fun SQLiteConnection.backfillDueInstants() {
        val dueInstants = mutableListOf<Pair<String, Long>>()
        prepare("SELECT id, due_at, time_zone FROM study_tasks WHERE due_at IS NOT NULL").use { statement ->
            while (statement.step()) {
                val dueAt = LocalDateTime.parse(statement.getText(1))
                val zone = TimeZone.of(statement.getText(2))
                dueInstants += statement.getText(0) to dueAt.toInstant(zone).toEpochMilliseconds()
            }
        }
        prepare("UPDATE study_tasks SET due_at_utc = ? WHERE id = ?").use { statement ->
            dueInstants.forEach { (id, dueAtUtc) ->
                statement.reset()
                statement.bindLong(1, dueAtUtc)
                statement.bindText(2, id)
                statement.step()
            }
        }
    }

    private fun SQLiteConnection.backfillReminderTriggers() {
        execSQL(
            """
            UPDATE reminders SET trigger_at_utc =
            (SELECT study_tasks.due_at_utc FROM study_tasks WHERE study_tasks.id = reminders.task_id)
            - COALESCE(lead_time, 0)
            """.trimIndent(),
        )
    }

    private val CREATE_SESSION_PROJECTION =
        """
        CREATE TABLE study_sessions_new (
            `id` TEXT NOT NULL, `subject_id` TEXT, `note` TEXT, `status` TEXT NOT NULL,
            `started_at` INTEGER NOT NULL, `ended_at` INTEGER, `device_id` TEXT NOT NULL,
            `updated_at` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`),
            FOREIGN KEY(`subject_id`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
        )
        """.trimIndent()

    private val PROJECT_SESSIONS_FROM_EVENTS =
        """
        INSERT INTO study_sessions_new (
            id, subject_id, note, status, started_at, ended_at, device_id, updated_at, deleted
        )
        SELECT s.id, s.subject_id, s.note,
            CASE WHEN EXISTS (
                SELECT 1 FROM session_events e WHERE e.session_id = s.id AND e.type = 'STOPPED'
            ) THEN 'STOPPED'
            WHEN (SELECT e.type FROM session_events e WHERE e.session_id = s.id
                ORDER BY e.sequence DESC LIMIT 1) IN ('STARTED', 'RESUMED') THEN 'RUNNING'
            ELSE 'PAUSED' END,
            COALESCE((SELECT MIN(e.wall_clock) FROM session_events e WHERE e.session_id = s.id), 0),
            (SELECT MAX(e.wall_clock) FROM session_events e
                WHERE e.session_id = s.id AND e.type = 'STOPPED'),
            '$MIGRATED_DEVICE_ID',
            COALESCE((SELECT MAX(e.wall_clock) FROM session_events e WHERE e.session_id = s.id), 0), 0
        FROM study_sessions s
        WHERE EXISTS (SELECT 1 FROM session_events e WHERE e.session_id = s.id)
        """.trimIndent()
}
