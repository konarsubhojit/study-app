package dev.studyflow.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.network.ApiResult
import dev.studyflow.core.network.StudyFlowApi
import dev.studyflow.core.network.auth.InMemoryTokenStore
import dev.studyflow.core.network.auth.TokenStore
import dev.studyflow.core.network.model.AccountDeletionReceiptDto
import dev.studyflow.core.network.model.StudySessionDto
import dev.studyflow.core.network.model.SubjectDto
import dev.studyflow.core.network.model.SyncDeltaDto
import dev.studyflow.core.network.model.SyncPushRequestDto
import dev.studyflow.core.network.model.SyncPushResponseDto
import dev.studyflow.core.network.model.TaskDto
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun studyFlowApi(): StudyFlowApi = FakeStudyFlowBackend()

    @Provides
    @Singleton
    fun tokenStore(): TokenStore = InMemoryTokenStore()
}

internal class FakeStudyFlowBackend(
    private val subjects: List<SubjectDto> =
        listOf(
            SubjectDto(id = "maths", name = "Mathematics", colorHex = "#3F51B5"),
            SubjectDto(id = "science", name = "Science", colorHex = "#00897B"),
        ),
    private val tasks: List<TaskDto> =
        listOf(
            TaskDto(id = "algebra", subjectId = "maths", title = "Practice algebra"),
            TaskDto(id = "physics", subjectId = "science", title = "Review mechanics"),
        ),
) : StudyFlowApi {
    override suspend fun subjects(): ApiResult<List<SubjectDto>> = ApiResult.Success(subjects)

    override suspend fun tasks(subjectId: String?): ApiResult<List<TaskDto>> =
        ApiResult.Success(tasks.filter { subjectId == null || it.subjectId == subjectId })

    override suspend fun uploadSession(session: StudySessionDto): ApiResult<StudySessionDto> =
        ApiResult.Success(session)

    override suspend fun pushSessionChanges(request: SyncPushRequestDto): ApiResult<SyncPushResponseDto> =
        ApiResult.Success(
            SyncPushResponseDto(acceptedIds = request.changes.map { it.id }),
        )

    override suspend fun sessionChanges(
        cursor: String?,
        limit: Int,
    ): ApiResult<SyncDeltaDto> = ApiResult.Success(SyncDeltaDto())

    override suspend fun deleteAccount(): ApiResult<AccountDeletionReceiptDto> =
        ApiResult.Success(
            AccountDeletionReceiptDto(acceptedAtIso = "2026-03-01T09:00:00Z", retentionWindowDays = 30),
        )
}
