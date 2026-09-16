package dev.studyflow.core.testing.network

import dev.studyflow.core.network.ApiConfig
import dev.studyflow.core.network.ApiEndpoint
import dev.studyflow.core.network.KtorStudyFlowApi
import dev.studyflow.core.network.MINIMUM_CLIENT_VERSION_HEADER
import dev.studyflow.core.network.StudyFlowApi
import dev.studyflow.core.network.auth.AuthTokens
import dev.studyflow.core.network.auth.InMemoryTokenStore
import dev.studyflow.core.network.auth.TokenStore
import dev.studyflow.core.network.http.studyFlowHttpClient
import dev.studyflow.core.network.model.ApiErrorDto
import dev.studyflow.core.network.model.StudyFlowJson
import dev.studyflow.core.network.model.StudySessionDto
import dev.studyflow.core.network.model.SubjectDto
import dev.studyflow.core.network.model.TaskDto
import dev.studyflow.core.network.retry.RetryPolicy
import dev.studyflow.core.network.version.ClientVersion
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.milliseconds

/**
 * A mock StudyFlow backend, in memory (issue #63).
 *
 * The same fake powers the network contract tests and a feature's tests, and it answers over the
 * *real* client — serialization, auth, retries, error mapping and all — so a feature test exercises
 * the code that will run in production rather than a hand-rolled stand-in that cannot fail the way
 * the network does.
 *
 * @property subjects what `GET /v1/subjects` returns.
 * @property tasks what `GET /v1/tasks` returns, before the `subjectId` filter is applied.
 * @property failWith when set, every call fails with this status instead of answering.
 * @property minimumClientVersion advertised in every response; set it above [clientVersion] to
 *   exercise the force-upgrade path.
 * @property clientVersion the version the fake client reports, as a real build would.
 */
public class FakeStudyFlowBackend(
    public var subjects: List<SubjectDto> = emptyList(),
    public var tasks: List<TaskDto> = emptyList(),
    public var failWith: HttpStatusCode? = null,
    public var minimumClientVersion: ClientVersion? = null,
    public val clientVersion: ClientVersion = ClientVersion(1, 0, 0),
) {
    /** Sessions the app has uploaded, in the order it uploaded them. */
    public val uploadedSessions: MutableList<StudySessionDto> = mutableListOf()

    /** Requested paths, so a test can assert that a repository did — or did not — call out. */
    public val requestedPaths: MutableList<String> = mutableListOf()

    private val config =
        ApiConfig(
            baseUrl = BASE_URL,
            clientVersion = clientVersion,
            // Tests must not spend seconds sleeping between retries.
            retry = RetryPolicy(maxRetries = 1, baseDelay = 1.milliseconds, maxDelay = 2.milliseconds),
        )

    /** A [StudyFlowApi] backed by this fake, ready to hand to a repository under test. */
    public fun api(
        tokenStore: TokenStore = InMemoryTokenStore(AuthTokens("test-access", "test-refresh")),
    ): StudyFlowApi =
        KtorStudyFlowApi(
            client =
                studyFlowHttpClient(
                    engine = MockEngine { request -> handle(request) },
                    config = config,
                    tokenStore = tokenStore,
                ),
            config = config,
        )

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        requestedPaths += path

        failWith?.let { status ->
            return respondJson("""{"code":"test_failure","message":"forced by the test"}""", status)
        }

        return when (path) {
            ApiEndpoint.ListSubjects.path -> {
                respondJson(StudyFlowJson.encodeToString(subjects))
            }

            ApiEndpoint.ListTasks.path -> {
                val subjectId = request.url.parameters["subjectId"]
                val matching = tasks.filter { subjectId == null || it.subjectId == subjectId }
                respondJson(StudyFlowJson.encodeToString(matching))
            }

            ApiEndpoint.UploadSession.path -> {
                val body = request.body.toByteArray().decodeToString()
                val session = StudyFlowJson.decodeFromString<StudySessionDto>(body)
                uploadedSessions += session
                respondJson(StudyFlowJson.encodeToString(session))
            }

            else -> {
                val error = ApiErrorDto(code = "not_found", message = path)
                respondJson(StudyFlowJson.encodeToString(error), HttpStatusCode.NotFound)
            }
        }
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData {
        val headers =
            Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                minimumClientVersion?.let { append(MINIMUM_CLIENT_VERSION_HEADER, it.toString()) }
            }
        return respond(content = body, status = status, headers = headers)
    }

    private companion object {
        const val BASE_URL = "https://mock.studyflow.test"
    }
}
