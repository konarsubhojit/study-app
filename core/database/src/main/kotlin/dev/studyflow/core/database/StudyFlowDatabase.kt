package dev.studyflow.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.studyflow.core.database.dao.FolderDao
import dev.studyflow.core.database.dao.MaterialDao
import dev.studyflow.core.database.dao.SessionDao
import dev.studyflow.core.database.dao.StudyTaskDao
import dev.studyflow.core.database.dao.SubjectDao
import dev.studyflow.core.database.entity.FolderEntity
import dev.studyflow.core.database.entity.MaterialEntity
import dev.studyflow.core.database.entity.ReminderEntity
import dev.studyflow.core.database.entity.SessionEventEntity
import dev.studyflow.core.database.entity.StudySessionEntity
import dev.studyflow.core.database.entity.StudyTaskEntity
import dev.studyflow.core.database.entity.SubjectEntity

@Database(
    entities = [
        SubjectEntity::class,
        FolderEntity::class,
        MaterialEntity::class,
        StudyTaskEntity::class,
        ReminderEntity::class,
        StudySessionEntity::class,
        SessionEventEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
public abstract class StudyFlowDatabase : RoomDatabase() {
    public abstract fun subjectDao(): SubjectDao

    public abstract fun folderDao(): FolderDao

    public abstract fun materialDao(): MaterialDao

    public abstract fun studyTaskDao(): StudyTaskDao

    public abstract fun sessionDao(): SessionDao

    public companion object {
        public const val NAME: String = "studyflow.db"
        public const val VERSION: Int = 2
    }
}
