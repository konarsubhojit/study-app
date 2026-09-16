package dev.studyflow.core.network.auth

import dev.studyflow.core.network.ApiConfig
import dev.studyflow.core.network.ApiEndpoint
import dev.studyflow.core.network.model.AuthTokensDto
import dev.studyflow.core.network.model.RefreshRequestDto
import dev.studyflow.core.network.model.StudyFlowJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException

/**
 * Calls `POST /v1/auth/refresh` (issue #63).
 *
 * It uses its own bare client — no auth plugin, no retry — because the refresh call is the one
 * request that must never be retried through the machinery that triggered it. Sharing the
 * application's client here is how a 401 turns into an infinite refresh loop.
 *
 * A refresh that fails for any reason returns `null`, which the caller reads as "the session is
 * over": pretending otherwise would keep a signed-out user staring at a spinner.
 */
public class HttpTokenRefresher(
    engine: HttpClientEngine,
    private val config: ApiConfig,
) : TokenRefresher {
    private val client: HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(StudyFlowJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeout.inWholeMilliseconds
            socketTimeoutMillis = config.socketTimeout.inWholeMilliseconds
            requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun refresh(refreshToken: String): AuthTokens? = try {
        val response: HttpResponse = client.post(config.urlOf(ApiEndpoint.RefreshTokens)) {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequestDto(refreshToken))
        }
        if (response.status.isSuccess()) response.body<AuthTokensDto>().toAuthTokens() else null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (ignored: Throwable) {
        null
    }
}

/** Maps the wire model onto the tokens the client holds. */
public fun AuthTokensDto.toAuthTokens(): AuthTokens =
    AuthTokens(accessToken = accessToken, refreshToken = refreshToken)
