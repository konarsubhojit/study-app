package dev.studyflow.core.network.error

import dev.studyflow.core.network.model.ApiErrorDto
import dev.studyflow.core.network.version.ClientVersion
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.UnknownHostException

@DisplayName("ApiErrorMapper")
class ApiErrorMapperTest {
    @ParameterizedTest(name = "status {0} maps to a message a user can act on")
    @ValueSource(ints = [400, 401, 402, 403, 404, 408, 409, 410, 418, 426, 429, 500, 502, 503, 504, 599])
    fun `every status maps to a user-facing message that hides the status`(status: Int) {
        val error = ApiErrorMapper.fromStatus(status)

        val text = error.message.defaultText
        assertFalse(text.contains(status.toString()), "message leaked the status code: $text")
        assertFalse(text.any(Char::isDigit), "message contains a number the user cannot act on: $text")
    }

    @Test
    fun `the server error code is kept for diagnostics but never for display`() {
        val error = ApiErrorMapper.fromStatus(403, ApiErrorDto(code = "subscription_required", message = "no plan"))

        assertEquals("subscription_required", error.code)
        assertEquals(UserFacingMessage.NotAllowed, error.message)
    }

    @Test
    fun `an unknown error code from a newer server still produces a message`() {
        val error = ApiErrorMapper.fromStatus(503, ApiErrorDto(code = "brand_new_code"))

        assertInstanceOf(ApiError.Server::class.java, error)
        assertEquals(UserFacingMessage.ServerProblem, error.message)
    }

    @Test
    fun `a removed endpoint is an upgrade prompt, because no retry can fix it`() {
        val error = ApiErrorMapper.fromStatus(410, ApiErrorDto(code = "endpoint_removed"))

        assertInstanceOf(ApiError.UpgradeRequired::class.java, error)
    }

    @Test
    fun `the minimum supported version travels with the upgrade error`() {
        val error = ApiErrorMapper.fromStatus(426, minimumSupported = ClientVersion(2, 1, 0))

        assertEquals(ClientVersion(2, 1, 0), (error as ApiError.UpgradeRequired).minimumSupported)
    }

    @Test
    fun `rate limiting carries the wait the server asked for`() {
        val error = ApiErrorMapper.fromStatus(429, retryAfter = 12)

        assertEquals(12L, (error as ApiError.RateLimited).retryAfter)
    }

    @Test
    fun `a request timeout is a slow connection, not a server fault`() {
        val error = ApiErrorMapper.fromThrowable(HttpRequestTimeoutException("https://example.test", 1_000))

        assertInstanceOf(ApiError.Timeout::class.java, error)
    }

    @Test
    fun `a transport failure reads as offline`() {
        assertInstanceOf(ApiError.Offline::class.java, ApiErrorMapper.fromThrowable(UnknownHostException("dns")))
        assertInstanceOf(ApiError.Offline::class.java, ApiErrorMapper.fromThrowable(IOException("reset")))
    }

    @Test
    fun `a contract breach is reported as malformed rather than offline`() {
        val error = ApiErrorMapper.fromThrowable(SerializationException("missing field"))

        assertInstanceOf(ApiError.Malformed::class.java, error)
    }

    @Test
    fun `anything else still has a message`() {
        val error = ApiErrorMapper.fromThrowable(IllegalStateException("boom"))

        assertEquals(UserFacingMessage.Unexpected, error.message)
    }
}
