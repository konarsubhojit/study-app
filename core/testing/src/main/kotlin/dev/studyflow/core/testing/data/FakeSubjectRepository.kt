package dev.studyflow.core.testing.data

import dev.studyflow.core.domain.subjects.SubjectRepository
import dev.studyflow.core.model.Subject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [SubjectRepository], the counterpart to [FakeTaskRepository]. */
public class FakeSubjectRepository : SubjectRepository {
    private val subjects = MutableStateFlow<List<Subject>>(emptyList())

    override fun observeSubjects(): Flow<List<Subject>> = subjects

    override suspend fun subject(id: String): Subject? = subjects.value.firstOrNull { it.id == id }

    public fun put(subject: Subject) {
        subjects.value = subjects.value.filterNot { it.id == subject.id } + subject
    }
}
