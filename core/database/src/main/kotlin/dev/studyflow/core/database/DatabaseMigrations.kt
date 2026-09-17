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
     * Version 3 grows the task model: all-day and priority metadata, tags, a checklist, links to a
     * material and a study session, soft deletion, sync timestamps, and any number of reminders per
     * task with either a lead time or an absolute trigger.
     *
     * Both tables are rebuilt rather than altered, because the new columns include foreign keys and
     * the recurrence rule moves from the reminder to the task it repeats with. The `due_at_utc` and
     * `trigger_at_utc` columns the list screens are indexed on are derived here: the conversion
     * needs the IANA time-zone rules for each row's own date, which SQLite has no access to, so the
     * existing local due times are resolved in Kotlin.
     */
    public val MIGRATION_2_3: Migration =
        object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.rebuildTaskTables()
                connection.backfillDueInstants()
                connection.backfillReminderTriggers()
            }
        }

    public val ALL: Array<Migration>
        get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

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
        execSQL("DROP TABLE reminders")
        execSQL("DROP TABLE study_tasks")
        execSQL("ALTER TABLE study_tasks_new RENAME TO study_tasks")
        execSQL("ALTER TABLE reminders_new RENAME TO reminders")
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
}
