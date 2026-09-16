package dev.studyflow.core.network

import dev.studyflow.core.network.auth.AuthTokens
import dev.studyflow.core.network.auth.InMemoryTokenStore
import dev.studyflow.core.network.auth.TokenRefresher
import dev.studyflow.core.network.auth.TokenStore
import dev.studyflow.core.network.http.studyFlowHttpClient
import dev.studyflow.core.network.retry.RetryPolicy
import dev.studyflow.core.network.version.ClientVersion
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.milliseconds

/** Shared mock-server plumbing for the contract tests. */
internal object MockBackend {
    const val BASE_URL: String = "https://api.test.studyflow.dev"

    val clientVersion: ClientVersion = ClientVersion(1, 4, 0)

    /** Short delays keep the retry tests honest without making them slow. */
    val config: ApiConfig =
        ApiConfig(
            baseUrl = BASE_URL,
            clientVersion = clientVersion,
            retry = RetryPolicy(maxRetries = 2, baseDelay = 1.milliseconds, maxDelay = 4.milliseconds),
        )

    fun api(
        tokenStore: TokenStore = InMemoryTokenStore(AuthTokens("access", "refresh")),
        tokenRefresher: TokenRefresher? = null,
        config: ApiConfig = MockBackend.config,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): StudyFlowApi =
        KtorStudyFlowApi(
            client =
                studyFlowHttpClient(
                    engine = MockEngine(handler),
                    config = config,
                    tokenStore = tokenStore,
                    tokenRefresher = tokenRefresher,
                ),
            config = config,
        )

    fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        minimumClientVersion: String? = null,
        retryAfter: String? = null,
    ): HttpResponseData =
        respond(
            content = body,
            status = status,
            headers =
                Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    minimumClientVersion?.let { append(MINIMUM_CLIENT_VERSION_HEADER, it) }
                    retryAfter?.let { append(HttpHeaders.RetryAfter, it) }
                },
        )
}
