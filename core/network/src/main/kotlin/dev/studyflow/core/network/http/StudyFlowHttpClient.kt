package dev.studyflow.core.network.http

import dev.studyflow.core.network.ApiConfig
import dev.studyflow.core.network.ApiEndpoint
import dev.studyflow.core.network.CLIENT_VERSION_HEADER
import dev.studyflow.core.network.auth.AuthTokens
import dev.studyflow.core.network.auth.TokenRefresher
import dev.studyflow.core.network.auth.TokenStore
import dev.studyflow.core.network.model.StudyFlowJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Builds the one [HttpClient] the app uses (issue #63).
 *
 * The engine is a parameter rather than a hard-coded choice: the app passes OkHttp, tests pass
 * Ktor's `MockEngine`, and neither has to know about the other. That is what lets the same client
 * code — plugins, headers, retries and all — be exercised by a contract test.
 *
 * `expectSuccess` stays off on purpose. Ktor's own exception for a failed status would bypass
 * [dev.studyflow.core.network.error.ApiErrorMapper], and an unmapped status is exactly the raw
 * HTTP code the UI must never see.
 *
 * @param tokenRefresher when `null`, a 401 simply fails; a client that cannot refresh must not
 *   pretend it can.
 */
public fun studyFlowHttpClient(
    engine: HttpClientEngine,
    config: ApiConfig,
    tokenStore: TokenStore,
    tokenRefresher: TokenRefresher? = null,
    json: Json = StudyFlowJson,
): HttpClient =
    HttpClient(engine) {
        expectSuccess = false

        install(ContentNegotiation) {
            json(json)
        }

        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeout.inWholeMilliseconds
            socketTimeoutMillis = config.socketTimeout.inWholeMilliseconds
            requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds
        }

        install(Auth) {
            bearer {
                loadTokens { tokenStore.tokens()?.toBearerTokens() }

                refreshTokens {
                    val refreshToken = oldTokens?.refreshToken ?: tokenStore.tokens()?.refreshToken
                    val refreshed = refreshToken?.let { tokenRefresher?.refresh(it) }
                    if (refreshed == null) {
                        // The refresh token is spent: dropping it now means the next call reports
                        // "sign in again" instead of looping through refresh on every request.
                        tokenStore.clear()
                        null
                    } else {
                        tokenStore.update(refreshed)
                        refreshed.toBearerTokens()
                    }
                }

                // The refresh call must stay anonymous, or a stale access token would be attached to
                // the very request meant to replace it.
                sendWithoutRequest { request ->
                    !request.url.hasPathOf(ApiEndpoint.RefreshTokens)
                }
            }
        }

        install(HttpRequestRetry) {
            maxRetries = config.retry.maxRetries

            retryIf { request, response ->
                config.retry.isRetryable(
                    status = response.status.value,
                    idempotent = request.method in IDEMPOTENT_METHODS,
                )
            }
            // A timeout can strike *after* the server processed the request, so an exception is
            // no proof that nothing happened. Only a request that cannot duplicate data is
            // replayed; cancellation is the caller leaving, never something to retry.
            retryOnExceptionIf { request, cause ->
                cause !is CancellationException && request.method in IDEMPOTENT_METHODS
            }

            delayMillis { attempt ->
                config.retry
                    .delayFor(
                        attempt = attempt,
                        retryAfter = response?.headers?.get(HttpHeaders.RetryAfter)?.toLongOrNull(),
                    ).inWholeMilliseconds
            }
        }

        defaultRequest {
            contentType(ContentType.Application.Json)
            header(CLIENT_VERSION_HEADER, config.clientVersion.toString())
        }
    }

/**
 * Methods that carry no side effect, so replaying one cannot duplicate the user's data.
 *
 * Derived from [ApiEndpoint] rather than restated, so the client and the contract cannot disagree
 * about which calls are safe to retry.
 */
private val IDEMPOTENT_METHODS: Set<HttpMethod> =
    ApiEndpoint.entries
        .filter(ApiEndpoint::isIdempotent)
        .mapTo(mutableSetOf()) { HttpMethod.parse(it.method.uppercase()) }

/** Path comparison that ignores the base URL, so the same rules hold for any environment. */
private fun URLBuilder.hasPathOf(endpoint: ApiEndpoint): Boolean =
    pathSegments.filter(String::isNotEmpty).joinToString(separator = "/") == endpoint.path.trim('/')

private fun AuthTokens.toBearerTokens(): BearerTokens = BearerTokens(accessToken, refreshToken)
