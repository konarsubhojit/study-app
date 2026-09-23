package dev.studyflow.core.database.lifecycle

import androidx.room.withTransaction
import dev.studyflow.core.common.time.DeviceIdProvider
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.entity.SessionEventEntity
import dev.studyflow.core.database.entity.asEntity
import dev.studyflow.core.database.entity.asExternalModel
import dev.studyflow.core.domain.lifecycle.DataEraser
import dev.studyflow.core.domain.lifecycle.ImportBatch
import dev.studyflow.core.domain.lifecycle.LocalDataReader
import dev.studyflow.core.domain.lifecycle.LocalDataSnapshot
import dev.studyflow.core.domain.lifecycle.LocalDataWriter
import dev.studyflow.core.domain.lifecycle.SessionWithLog
import javax.inject.Inject

/**
 * Reads and writes the whole local dataset for export and restore (issue #78).
 *
 * Deliberately built on the existing DAOs and entity mappings rather than on new tables: an
 * archive is a view of what the app already stores, so anything it needed that the schema could
 * not already express would be a sign the archive had drifted from the app's own model.
 */
public class RoomDataLifecycleStore
    @Inject
    constructor(
        private val database: StudyFlowDatabase,
        private val deviceIdProvider: DeviceIdProvider,
    ) : LocalDataReader,
        LocalDataWriter {
        /**
         * Reads everything in one transaction.
         *
         * An export taken while the user keeps studying would otherwise be able to contain a
         * session referring to a task renamed halfway through the same read.
         *
         * `updatedAt` is taken from the stored column rather than from the projection, for the
         * reason `asSyncRecord` documents: the reducer derives it from the last event, while a
         * metadata-only edit appends no event at all, and last-write-wins has to compare the last
         * time the row was actually written.
         */
        override suspend fun readSnapshot(): LocalDataSnapshot =
            database.withTransaction {
                LocalDataSnapshot(
                    deviceId = deviceIdProvider.current(),
                    subjects = database.subjectDao().allSubjects().map { it.asExternalModel() },
                    sessions =
                        database.sessionDao().allSessions().mapNotNull { stored ->
                            stored.asExternalModel()?.let { projection ->
                                SessionWithLog(
                                    session = projection.copy(updatedAt = stored.session.updatedAt),
                                    events = stored.events.map(SessionEventEntity::asExternalModel),
                                )
                            }
                        },
                    tasks = database.studyTaskDao().allTasks().map { it.asExternalModel() },
                    materials = database.materialDao().allMaterials().map { it.asExternalModel() },
                )
            }

        /**
         * Applies a resolved import atomically, so a restore interrupted halfway is entirely
         * absent rather than half-present — the user's next move is to try the same archive again.
         */
        override suspend fun applyImport(batch: ImportBatch) {
            database.withTransaction {
                database.subjectDao().upsertAll(batch.subjects.map { it.asEntity() })
                database.studyTaskDao().saveAll(batch.tasks.map { it.asEntity() })
                database.materialDao().upsertAll(batch.materials.map { it.asEntity() })
                database.sessionDao().restore(
                    sessions = batch.sessions.map { it.session.asEntity() },
                    events = batch.sessions.flatMap { it.events }.map { it.asEntity() },
                )
            }
        }
    }

/**
 * Empties every table, for account deletion (issue #78).
 *
 * `clearAllTables` rather than a `DELETE` per table: it is the one operation that also covers
 * tables a future migration adds, and forgetting to extend a hand-written list is exactly the
 * omission that would leave a user's data behind after they asked for it to be gone.
 */
public class RoomDataEraser
    @Inject
    constructor(
        private val database: StudyFlowDatabase,
    ) : DataEraser {
        override val name: String = "database"

        override suspend fun erase() {
            database.clearAllTables()
        }
    }
