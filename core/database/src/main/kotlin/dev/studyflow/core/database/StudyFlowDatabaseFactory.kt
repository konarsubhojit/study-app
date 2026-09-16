package dev.studyflow.core.database

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.room.Room

/** Controls recovery when an installed database has no registered migration path. */
public enum class MissingMigrationPolicy {
    /** Preserve user data or fail loudly. This is the production default. */
    FAIL,

    /** Recreate the database. Accepted only when Android marks the application debuggable. */
    DESTRUCTIVE_FOR_DEVELOPMENT,
}

/**
 * Creates the process-wide database.
 *
 * Destructive fallback is guarded by the installed application's `FLAG_DEBUGGABLE`, rather than a
 * caller-provided boolean. A release APK therefore cannot accidentally opt into data loss.
 */
public object StudyFlowDatabaseFactory {
    public fun create(
        context: Context,
        missingMigrationPolicy: MissingMigrationPolicy = MissingMigrationPolicy.FAIL,
    ): StudyFlowDatabase {
        enforceMigrationPolicy(
            isApplicationDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            policy = missingMigrationPolicy,
        )

        val builder =
            Room
                .databaseBuilder(
                    context.applicationContext,
                    StudyFlowDatabase::class.java,
                    StudyFlowDatabase.NAME,
                )
        DatabaseMigrations.ALL.forEach(builder::addMigrations)

        if (missingMigrationPolicy == MissingMigrationPolicy.DESTRUCTIVE_FOR_DEVELOPMENT) {
            builder.fallbackToDestructiveMigration(dropAllTables = true)
        }
        return builder.build()
    }

    internal fun enforceMigrationPolicy(
        isApplicationDebuggable: Boolean,
        policy: MissingMigrationPolicy,
    ) {
        check(isApplicationDebuggable || policy == MissingMigrationPolicy.FAIL) {
            "Destructive Room migrations are forbidden in non-debuggable builds"
        }
    }
}
