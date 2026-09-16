package dev.studyflow.core.network

import dev.studyflow.core.network.model.StudySessionDto
import dev.studyflow.core.network.model.SubjectDto
import dev.studyflow.core.network.model.TaskDto

/**
 * The typed contract between the app and the StudyFlow backend (issue #63).
 *
 * One function per operation in `docs/api/openapi.yaml`. Repositories depend on this interface, so
 * a feature test swaps in a fake without a socket in sight, and no layer above the data layer ever
 * sees an [io.ktor.client.HttpClient].
 */
public interface StudyFlowApi {
    /** `GET /v1/subjects` — every subject the signed-in user owns. */
    public suspend fun subjects(): ApiResult<List<SubjectDto>>

    /**
     * `GET /v1/tasks` — the user's study tasks.
     *
     * @param subjectId restricts the result to one subject when given.
     */
    public suspend fun tasks(subjectId: String? = null): ApiResult<List<TaskDto>>

    /** `POST /v1/sessions` — uploads a finished study session and returns the stored copy. */
    public suspend fun uploadSession(session: StudySessionDto): ApiResult<StudySessionDto>
}
