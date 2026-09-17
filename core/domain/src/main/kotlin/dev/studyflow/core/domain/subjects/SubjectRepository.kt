package dev.studyflow.core.domain.subjects

import dev.studyflow.core.model.Subject
import kotlinx.coroutines.flow.Flow

/**
 * The subjects the user tracks time and tasks against.
 *
 * Mirrors [dev.studyflow.core.domain.tasks.TaskRepository]'s shape rather than introducing a new
 * one: an observable list for screens, plus a one-shot lookup for a single id, is all a subject's
 * colour and name need once a task or a reminder already knows which subject it belongs to.
 */
public interface SubjectRepository {
    /** Every subject, archived ones included; a screen decides whether to filter them out. */
    public fun observeSubjects(): Flow<List<Subject>>

    /** One subject by id, or `null` when it does not exist — used to resolve a task's colour. */
    public suspend fun subject(id: String): Subject?
}
