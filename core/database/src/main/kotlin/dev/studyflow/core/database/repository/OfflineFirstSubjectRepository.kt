package dev.studyflow.core.database.repository

import dev.studyflow.core.database.dao.SubjectDao
import dev.studyflow.core.database.entity.asExternalModel
import dev.studyflow.core.domain.subjects.SubjectRepository
import dev.studyflow.core.model.Subject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The local database is the source of truth for subjects, same as [OfflineFirstTaskRepository]. */
public class OfflineFirstSubjectRepository(
    private val dao: SubjectDao,
) : SubjectRepository {
    override fun observeSubjects(): Flow<List<Subject>> =
        dao.observeAll().map { rows ->
            rows.map { it.asExternalModel() }
        }

    override suspend fun subject(id: String): Subject? = dao.getById(id)?.asExternalModel()
}
