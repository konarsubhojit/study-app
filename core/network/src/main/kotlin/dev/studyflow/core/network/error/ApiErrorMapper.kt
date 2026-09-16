package dev.studyflow.core.network.error

import dev.studyflow.core.network.model.ApiErrorDto
import dev.studyflow.core.network.version.ClientVersion
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.serialization.JsonConvertException
import kotlinx.serialization.SerializationException
import java.io.IOException

/**
 * Turns everything the transport can produce into an [ApiError] (issue #63).
 *
 * This is the only place in the app that is allowed to know what an HTTP status code means. Because
 * the mapping is total — every status, every exception — no raw code can leak past it into a
 * ViewModel, which is what the "no HTTP codes in the UI" acceptance criterion asks for.
 */
public object ApiErrorMapper {
    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val NOT_FOUND = 404
    private const val REQUEST_TIMEOUT = 408
    private const val CONFLICT = 409
    private const val GONE = 410
    private const val UPGRADE_REQUIRED = 426
    private const val TOO_MANY_REQUESTS = 429
    private val serverErrors = 500..599

    /**
     * Maps a non-successful response.
     *
     * @param status the HTTP status code.
     * @param body the structured error the server sent, when it sent one.
     * @param retryAfter value of the `Retry-After` header in seconds, when present.
     * @param minimumSupported value of `X-Minimum-Client-Version`, when present.
     */
    public fun fromStatus(
        status: Int,
        body: ApiErrorDto? = null,
        retryAfter: Long? = null,
        minimumSupported: ClientVersion? = null,
    ): ApiError {
        val code = body?.code
        return when (status) {
            UNAUTHORIZED -> ApiError.Unauthorized(code)
            FORBIDDEN -> ApiError.Forbidden(code)
            NOT_FOUND -> ApiError.NotFound(code)
            REQUEST_TIMEOUT -> ApiError.Timeout()
            CONFLICT -> ApiError.Conflict(code)
            // A removed endpoint and a client the server refuses to serve are the same problem for
            // the user: this build cannot talk to the backend any more, so send them to the store.
            GONE, UPGRADE_REQUIRED -> ApiError.UpgradeRequired(minimumSupported, code)
            TOO_MANY_REQUESTS -> ApiError.RateLimited(retryAfter, code)
            in serverErrors -> ApiError.Server(code)
            else -> ApiError.Unexpected(code)
        }
    }

    /**
     * Maps a failure that happened before a status was known.
     *
     * Coroutine cancellation is never an API error and must be rethrown by the caller before this
     * is reached.
     */
    public fun fromThrowable(throwable: Throwable): ApiError = when (throwable) {
        is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException ->
            ApiError.Timeout(throwable)

        // A contract breach — a missing or mistyped field — not an unknown field, which is ignored.
        is JsonConvertException, is SerializationException -> ApiError.Malformed(throwable)

        is IOException -> ApiError.Offline(throwable)
        else -> ApiError.Unexpected(cause = throwable)
    }
}
