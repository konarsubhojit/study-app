package dev.studyflow.core.network

import dev.studyflow.core.network.error.ApiError
import dev.studyflow.core.network.error.ApiErrorMapper
import dev.studyflow.core.network.model.ApiErrorDto
import dev.studyflow.core.network.model.StudySessionDto
import dev.studyflow.core.network.model.SubjectDto
import dev.studyflow.core.network.model.SyncDeltaDto
import dev.studyflow.core.network.model.SyncPushRequestDto
import dev.studyflow.core.network.model.SyncPushResponseDto
import dev.studyflow.core.network.model.TaskDto
import dev.studyflow.core.network.version.ClientVersion
import dev.studyflow.core.network.version.MinimumClientPolicy
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * The [StudyFlowApi] implementation over Ktor (issue #63).
 *
 * Every call goes through [execute], which is where the three things that must never be forgotten
 * happen in one place: the minimum-supported-client check, the structured error mapping, and
 * turning a thrown transport failure into an [ApiError]. A new endpoint therefore cannot forget
 * them.
 */
public class KtorStudyFlowApi(
    private val client: HttpClient,
    private val config: ApiConfig,
) : StudyFlowApi {
    override suspend fun subjects(): ApiResult<List<SubjectDto>> = execute(ApiEndpoint.ListSubjects)

    override suspend fun tasks(subjectId: String?): ApiResult<List<TaskDto>> =
        execute(ApiEndpoint.ListTasks) {
            subjectId?.let { parameter("subjectId", it) }
        }

    override suspend fun uploadSession(session: StudySessionDto): ApiResult<StudySessionDto> =
        execute(ApiEndpoint.UploadSession) {
            setBody(session)
        }

    override suspend fun pushSessionChanges(request: SyncPushRequestDto): ApiResult<SyncPushResponseDto> =
        execute(ApiEndpoint.PushSessionChanges) {
            setBody(request)
        }

    override suspend fun sessionChanges(
        cursor: String?,
        limit: Int,
    ): ApiResult<SyncDeltaDto> =
        execute(ApiEndpoint.PullSessionChanges) {
            cursor?.let { parameter("cursor", it) }
            parameter("limit", limit)
        }

    // Ktor's transport throws for everything from a refused connection to a malformed body, and a
    // caller cannot act on any of it, so the whole surface is mapped rather than caught by type.
    @Suppress("TooGenericExceptionCaught")
    private suspend inline fun <reified T> execute(
        endpoint: ApiEndpoint,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): ApiResult<T> =
        try {
            val response =
                client.request(config.urlOf(endpoint)) {
                    method = HttpMethod.parse(endpoint.method.uppercase())
                    configure()
                }
            interpret(response)
        } catch (cancellation: CancellationException) {
            // Cancellation is the caller leaving the screen, never a failure to report to them.
            throw cancellation
        } catch (failure: Throwable) {
            ApiResult.Failure(ApiErrorMapper.fromThrowable(failure))
        }

    private suspend inline fun <reified T> interpret(response: HttpResponse): ApiResult<T> {
        val minimumSupported = ClientVersion.parseOrNull(response.headers[MINIMUM_CLIENT_VERSION_HEADER])
        // Checked even on a successful response: the server announces the cut-off before it
        // enforces it, which is what makes the upgrade prompt a warning rather than a dead end.
        if (MinimumClientPolicy.isUpgradeRequired(config.clientVersion, minimumSupported)) {
            return ApiResult.Failure(ApiError.UpgradeRequired(minimumSupported))
        }

        return if (response.status.isSuccess()) {
            ApiResult.Success(response.body())
        } else {
            ApiResult.Failure(
                ApiErrorMapper.fromStatus(
                    status = response.status.value,
                    body = response.errorBodyOrNull(),
                    retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
                    minimumSupported = minimumSupported,
                ),
            )
        }
    }
}

/**
 * Reads the structured error body, tolerating a server that sent none.
 *
 * A proxy answering with an HTML error page is still a mapped [ApiError]; losing the server's
 * error code is a worse outcome only for diagnostics, never for the user.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private suspend fun HttpResponse.errorBodyOrNull(): ApiErrorDto? =
    try {
        body<ApiErrorDto>()
    } catch (ignored: Throwable) {
        null
    }
