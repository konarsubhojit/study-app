package dev.studyflow.core.network

import dev.studyflow.core.network.MockBackend.json
import dev.studyflow.core.network.auth.AuthTokens
import dev.studyflow.core.network.auth.InMemoryTokenStore
import dev.studyflow.core.network.error.ApiError
import dev.studyflow.core.network.error.UserFacingMessage
import dev.studyflow.core.network.model.PasskeyRegistrationRequestDto
import dev.studyflow.core.network.model.SignInCredentialDto
import dev.studyflow.core.network.model.StudySessionDto
import dev.studyflow.core.network.model.SyncPushRequestDto
import dev.studyflow.core.network.model.SyncRecordPushRequestDto
import dev.studyflow.core.network.model.SyncSessionDto
import dev.studyflow.core.network.version.ClientVersion
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("KtorStudyFlowApi against a mock server")
class KtorStudyFlowApiTest {
    @Test
    fun `a sign-in exchange sends a discriminated proof without bearer authentication`() =
        runTest {
            var authorization: String? = null
            var body = ""
            val api =
                MockBackend.api { request ->
                    authorization = request.headers["Authorization"]
                    body = request.body.toByteArray().decodeToString()
                    json("""{"accessToken":"access","refreshToken":"refresh","expiresInSeconds":3600}""")
                }

            val result = api.signIn(SignInCredentialDto.Passkey("assertion-secret"))

            assertEquals("access", result.valueOrNull()?.accessToken)
            assertEquals("""{"type":"passkey","assertion":"assertion-secret"}""", body)
            assertEquals(null, authorization)
        }

    @Test
    fun `passkey registration is authenticated, unlike sign-in, and posts the platform response verbatim`() =
        runTest {
            var authorization: String? = null
            var path = ""
            var body = ""
            val api =
                MockBackend.api { request ->
                    authorization = request.headers["Authorization"]
                    path = request.url.encodedPath
                    body = request.body.toByteArray().decodeToString()
                    json("""{"credentialId":"cred-1","createdAt":"2026-03-01T09:00:00Z"}""", HttpStatusCode.Created)
                }

            val result = api.registerPasskey(PasskeyRegistrationRequestDto("""{"id":"cred-1"}"""))

            assertEquals("cred-1", result.valueOrNull()?.credentialId)
            assertEquals(ApiEndpoint.RegisterPasskey.path, path)
            assertEquals("""{"registrationResponseJson":"{\"id\":\"cred-1\"}"}""", body)
            assertTrue(
                authorization?.startsWith("Bearer ") == true,
                "registration is an authenticated operation, unlike the sign-in exchange above",
            )
        }

    @Test
    fun `sign-in proofs are redacted in diagnostic strings`() {
        assertEquals("Passkey(redacted)", SignInCredentialDto.Passkey("assertion-secret").toString())
        assertEquals("Google(redacted)", SignInCredentialDto.Google("token-secret").toString())
    }

    @Test
    fun `a documented response decodes into typed models`() =
        runTest {
            val api =
                MockBackend.api {
                    json("""[{"id":"s1","name":"Maths","colorHex":"#2E7D32"}]""")
                }

            val result = api.subjects()

            assertEquals(listOf("Maths"), result.valueOrNull()?.map { it.name })
        }

    @Test
    fun `the query parameter documented for tasks is sent`() =
        runTest {
            var requestedUrl = ""
            val api =
                MockBackend.api { request ->
                    requestedUrl = request.url.toString()
                    json("[]")
                }

            api.tasks(subjectId = "s1")

            assertTrue(requestedUrl.endsWith("/v1/tasks?subjectId=s1"), "was $requestedUrl")
        }

    @Test
    fun `record delta requests send the persisted opaque cursor`() =
        runTest {
            var cursor: String? = null
            var limit: String? = null
            val api =
                MockBackend.api { request ->
                    cursor = request.url.parameters["cursor"]
                    limit = request.url.parameters["limit"]
                    json("""{"changes":[],"nextCursor":"records-5","hasMore":false}""")
                }

            api.recordChanges(cursor = "records-4", limit = 50)

            assertEquals("records-4", cursor)
            assertEquals("50", limit)
        }

    @Test
    fun `every request carries the client version so the server can police its population`() =
        runTest {
            var header: String? = null
            val api =
                MockBackend.api { request ->
                    header = request.headers[CLIENT_VERSION_HEADER]
                    json("[]")
                }

            api.subjects()

            assertEquals(MockBackend.clientVersion.toString(), header)
        }

    @Test
    fun `an expired access token is refreshed and the call succeeds without the caller noticing`() =
        runTest {
            val freshAccessToken = "fresh"
            val store = InMemoryTokenStore(AuthTokens("expired", "refresh"))
            val api =
                MockBackend.api(
                    tokenStore = store,
                    tokenRefresher = { AuthTokens(freshAccessToken, "refresh-2") },
                ) { request ->
                    if (request.headers["Authorization"]?.endsWith(freshAccessToken) == true) {
                        json("""[{"id":"s1","name":"Maths"}]""")
                    } else {
                        json("""{"code":"token_expired","message":"expired"}""", HttpStatusCode.Unauthorized)
                    }
                }

            val result = api.subjects()

            assertEquals(listOf("Maths"), result.valueOrNull()?.map { it.name })
            assertEquals(freshAccessToken, store.tokens()?.accessToken)
        }

    @Test
    fun `a spent refresh token signs the user out instead of looping`() =
        runTest {
            val store = InMemoryTokenStore(AuthTokens("expired", "refresh"))
            val api =
                MockBackend.api(tokenStore = store, tokenRefresher = { null }) {
                    json("""{"code":"token_expired","message":"expired"}""", HttpStatusCode.Unauthorized)
                }

            val error = api.subjects().errorOrNull()

            assertInstanceOf(ApiError.Unauthorized::class.java, error)
            assertEquals(UserFacingMessage.SignInRequired, error?.message)
            assertEquals(null, store.tokens())
        }

    @Test
    fun `a failing server is retried, and the final failure is a user-facing error`() =
        runTest {
            val attempts = AtomicInteger()
            val api =
                MockBackend.api {
                    attempts.incrementAndGet()
                    json("""{"code":"upstream_unavailable","message":"boom"}""", HttpStatusCode.ServiceUnavailable)
                }

            val error = api.subjects().errorOrNull()

            assertEquals(1 + MockBackend.config.retry.maxRetries, attempts.get())
            assertInstanceOf(ApiError.Server::class.java, error)
            assertEquals("upstream_unavailable", error?.code)
        }

    @Test
    fun `a write is never replayed, because a duplicate session is worse than a failure`() =
        runTest {
            val attempts = AtomicInteger()
            val api =
                MockBackend.api {
                    attempts.incrementAndGet()
                    json("""{"code":"upstream_unavailable","message":"boom"}""", HttpStatusCode.ServiceUnavailable)
                }

            api.uploadSession(
                StudySessionDto(
                    id = "session-1",
                    subjectId = "s1",
                    startedAtIso = "2026-03-01T10:00:00Z",
                    focusedSeconds = 1_500,
                ),
            )

            assertEquals(1, attempts.get())
        }

    @Test
    fun `a sign-in exchange is never replayed`() =
        runTest {
            val signInAttempts = AtomicInteger()
            val signInApi =
                MockBackend.api {
                    signInAttempts.incrementAndGet()
                    json(
                        """{"code":"upstream_unavailable","message":"boom"}""",
                        HttpStatusCode.ServiceUnavailable,
                    )
                }

            signInApi.signIn(SignInCredentialDto.Google("token-secret"))

            assertEquals(1, signInAttempts.get())
        }

    @Test
    fun `a write that times out is not replayed, because the server may have accepted it`() =
        runTest {
            val attempts = AtomicInteger()
            val api =
                MockBackend.api {
                    attempts.incrementAndGet()
                    throw IOException("connection reset after the request was sent")
                }

            val error =
                api
                    .uploadSession(
                        StudySessionDto(
                            id = "session-1",
                            subjectId = "s1",
                            startedAtIso = "2026-03-01T10:00:00Z",
                            focusedSeconds = 1_500,
                        ),
                    ).errorOrNull()

            assertEquals(1, attempts.get())
            assertInstanceOf(ApiError.Offline::class.java, error)
        }

    @Test
    fun `a conflict is explained rather than retried`() =
        runTest {
            val api =
                MockBackend.api {
                    json("""{"code":"session_exists","message":"already stored"}""", HttpStatusCode.Conflict)
                }

            val error =
                api
                    .uploadSession(
                        StudySessionDto("session-1", "s1", "2026-03-01T10:00:00Z", focusedSeconds = 60),
                    ).errorOrNull()

            assertInstanceOf(ApiError.Conflict::class.java, error)
            assertEquals(UserFacingMessage.Conflict, error?.message)
        }

    @Test
    fun `a server that refuses this build asks the user to upgrade`() =
        runTest {
            val api =
                MockBackend.api {
                    json("""{"code":"client_too_old","message":"upgrade"}""", HttpStatusCode.UpgradeRequired)
                }

            val error = api.subjects().errorOrNull()

            assertInstanceOf(ApiError.UpgradeRequired::class.java, error)
        }

    @Test
    fun `the minimum-client header cuts this build off before the server starts rejecting it`() =
        runTest {
            val api =
                MockBackend.api {
                    json("""[{"id":"s1","name":"Maths"}]""", minimumClientVersion = "2.0.0")
                }

            val error = api.subjects().errorOrNull()

            assertInstanceOf(ApiError.UpgradeRequired::class.java, error)
            assertEquals(ClientVersion(2, 0, 0), (error as ApiError.UpgradeRequired).minimumSupported)
        }

    @Test
    fun `a supported build is not disturbed by the minimum-client header`() =
        runTest {
            val api =
                MockBackend.api {
                    json("""[{"id":"s1","name":"Maths"}]""", minimumClientVersion = "1.0.0")
                }

            assertEquals(listOf("s1"), api.subjects().valueOrNull()?.map { it.id })
        }

    @Test
    fun `a rate-limited call reports the wait the server asked for`() =
        runTest {
            val api =
                MockBackend.api {
                    json(
                        body = """{"code":"rate_limited","message":"slow down"}""",
                        status = HttpStatusCode.TooManyRequests,
                        retryAfter = "30",
                    )
                }

            val error =
                api
                    .uploadSession(
                        StudySessionDto("session-1", "s1", "2026-03-01T10:00:00Z", focusedSeconds = 60),
                    ).errorOrNull()

            assertInstanceOf(ApiError.RateLimited::class.java, error)
            assertEquals(30L, (error as ApiError.RateLimited).retryAfter)
        }

    @Test
    fun `an unreachable server reads as offline, not as a crash`() =
        runTest {
            val api = MockBackend.api { throw IOException("no route to host") }

            val error = api.subjects().errorOrNull()

            assertInstanceOf(ApiError.Offline::class.java, error)
        }

    @Test
    fun `a response missing a required field is a contract breach, not a crash`() =
        runTest {
            val api = MockBackend.api { json("""[{"name":"Maths"}]""") }

            val error = api.subjects().errorOrNull()

            assertInstanceOf(ApiError.Malformed::class.java, error)
        }

    @Test
    fun `an error body the server did not send still produces a user-facing message`() =
        runTest {
            val api =
                MockBackend.api {
                    json("<html>Bad Gateway</html>", HttpStatusCode.BadGateway)
                }

            val error = api.subjects().errorOrNull()

            assertInstanceOf(ApiError.Server::class.java, error)
            assertEquals(UserFacingMessage.ServerProblem, error?.message)
        }

    /**
     * The unattended case behind issue #191: an access token expires while the sync worker is
     * pushing, so nobody is watching a screen to sign in again. The refresh has to happen inside
     * the same call, and the pushed batch has to go out again unchanged, or the queue is retried
     * forever with a credential that can never be accepted.
     */
    @Test
    fun `an expired token during a background push is refreshed and the same batch is retried`() =
        runTest {
            val store = InMemoryTokenStore(AuthTokens("expired", "refresh"))
            val refreshes = AtomicInteger()
            val bodies = mutableListOf<String>()
            val api =
                MockBackend.api(
                    tokenStore = store,
                    tokenRefresher = {
                        refreshes.incrementAndGet()
                        AuthTokens("fresh", "refresh-2")
                    },
                ) { request ->
                    bodies += request.body.toByteArray().decodeToString()
                    if (request.headers["Authorization"]?.endsWith("fresh") == true) {
                        json("""{"acceptedIds":["session-1"],"rejectedIds":[]}""")
                    } else {
                        json("""{"code":"authentication_required","message":"expired"}""", HttpStatusCode.Unauthorized)
                    }
                }

            val result = api.pushSessionChanges(pushRequest())

            assertEquals(listOf("session-1"), result.valueOrNull()?.acceptedIds)
            assertEquals(1, refreshes.get())
            assertEquals("fresh", store.tokens()?.accessToken)
            assertEquals(2, bodies.size, "the push is sent again after the refresh")
            assertEquals(bodies.first(), bodies.last(), "the retried batch is the one that was queued")
        }

    @Test
    fun `a background push whose refresh token is spent fails permanently instead of looping`() =
        runTest {
            val store = InMemoryTokenStore(AuthTokens("expired", "spent"))
            val attempts = AtomicInteger()
            val api =
                MockBackend.api(tokenStore = store, tokenRefresher = { null }) {
                    attempts.incrementAndGet()
                    json("""{"code":"authentication_required","message":"expired"}""", HttpStatusCode.Unauthorized)
                }

            val error = api.pushSessionChanges(pushRequest()).errorOrNull()

            assertInstanceOf(ApiError.Unauthorized::class.java, error)
            assertEquals(UserFacingMessage.SignInRequired, error?.message)
            assertEquals(null, store.tokens(), "a spent session is dropped so the user is asked to sign in")
            assertEquals(1, attempts.get(), "an unauthorised push is not retried")
        }

    // Two real threads are the point: the refresh mutex is what stops a token storm, and a single
    // test dispatcher would interleave the two pushes rather than race them.
    @Suppress("InjectDispatcher")
    @Test
    fun `two pushes meeting the same expired token refresh it once`() =
        runTest {
            val store = InMemoryTokenStore(AuthTokens("expired", "refresh"))
            val refreshes = AtomicInteger()
            val api =
                MockBackend.api(
                    tokenStore = store,
                    tokenRefresher = {
                        refreshes.incrementAndGet()
                        AuthTokens("fresh", "refresh-2")
                    },
                ) { request ->
                    if (request.headers["Authorization"]?.endsWith("fresh") == true) {
                        json("""{"acceptedIds":[],"rejectedIds":[]}""")
                    } else {
                        json("""{"code":"authentication_required","message":"expired"}""", HttpStatusCode.Unauthorized)
                    }
                }

            val results =
                coroutineScope {
                    listOf(
                        async(Dispatchers.Default) { api.pushSessionChanges(pushRequest()) },
                        async(Dispatchers.Default) {
                            api.pushRecordChanges(
                                SyncRecordPushRequestDto(deviceId = "device-a", changes = emptyList()),
                            )
                        },
                    ).awaitAll()
                }

            assertTrue(results.all { it.valueOrNull() != null }, "both pushes completed: $results")
            assertEquals(1, refreshes.get(), "a second 401 reuses the refreshed session rather than starting a storm")
        }

    private fun pushRequest(): SyncPushRequestDto =
        SyncPushRequestDto(
            deviceId = "device-a",
            changes =
                listOf(
                    SyncSessionDto(
                        id = "session-1",
                        deviceId = "device-a",
                        updatedAtIso = "2026-03-01T10:00:00Z",
                        startedAtIso = "2026-03-01T09:00:00Z",
                        endedAtIso = "2026-03-01T09:30:00Z",
                        status = "STOPPED",
                        countedMillis = 1_800_000,
                    ),
                ),
        )
}
