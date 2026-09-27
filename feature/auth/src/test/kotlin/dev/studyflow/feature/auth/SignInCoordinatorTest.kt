package dev.studyflow.feature.auth

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import dev.studyflow.core.network.auth.AuthState
import dev.studyflow.core.network.auth.InMemoryTokenStore
import dev.studyflow.core.network.model.SignInCredentialDto
import dev.studyflow.core.testing.network.FakeStudyFlowBackend
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import javax.inject.Provider

class SignInCoordinatorTest {
    private val backend = FakeStudyFlowBackend()
    private val tokens = InMemoryTokenStore()
    private val coordinator =
        SignInCoordinator(backend.api(tokens), AuthSession(tokens), Provider { GoogleSignInConfig("web-client-id") })

    @Test
    fun `Google sign in exchanges a proof and persists the session`() =
        runTest {
            assertEquals(AuthState.LocalOnly, tokens.authState.value)

            val result =
                coordinator.signIn { request, config ->
                    assertEquals(backend.signInChallenge.requestJson, request)
                    assertEquals("web-client-id", config.serverClientId)
                    SignInCredential.GoogleIdToken("id-token")
                }

            assertEquals(SignInOutcome.SignedIn, result)
            assertEquals(listOf(SignInCredentialDto.Google("id-token")), backend.receivedSignInCredentials)
            assertEquals("test-refresh", tokens.tokens()?.refreshToken)
            assertEquals(AuthState.SignedIn, tokens.authState.value)
        }

    @Test
    fun `passkey assertions use the same exchange`() =
        runTest {
            val result = coordinator.signIn { _, _ -> SignInCredential.Passkey("assertion") }

            assertEquals(SignInOutcome.SignedIn, result)
            assertEquals(listOf(SignInCredentialDto.Passkey("assertion")), backend.receivedSignInCredentials)
        }

    @Test
    fun `cancelled credential picker leaves local mode intact`() =
        runTest {
            val result = coordinator.signIn { _, _ -> throw GetCredentialCancellationException() }

            assertEquals(SignInOutcome.Cancelled, result)
            assertEquals(AuthState.LocalOnly, tokens.authState.value)
            assertEquals(emptyList<SignInCredentialDto>(), backend.receivedSignInCredentials)
        }

    @Test
    fun `no available credential is routine and leaves local mode intact`() =
        runTest {
            val result =
                coordinator.signIn {
                    _,
                    _,
                    ->
                    throw NoSignInCredentialAvailableException(NoCredentialException())
                }

            assertEquals(SignInOutcome.NoCredential, result)
            assertEquals(AuthState.LocalOnly, tokens.authState.value)
        }

    @Test
    fun `missing Google configuration does not affect local-only account state`() =
        runTest {
            val unconfigured =
                SignInCoordinator(backend.api(tokens), AuthSession(tokens), Provider { error("Missing web client id") })

            assertEquals(SignInOutcome.Failed, unconfigured.signIn { _, _ -> SignInCredential.GoogleIdToken("unused") })
            assertEquals(AuthState.LocalOnly, tokens.authState.value)
            assertEquals(emptyList<String>(), backend.requestedPaths)
        }

    @Test
    fun `rejected credential is distinguished from network failure`() =
        runTest {
            backend.signInFailure = HttpStatusCode.Unauthorized
            assertEquals(
                SignInOutcome.Rejected,
                coordinator.signIn { _, _ -> SignInCredential.GoogleIdToken("id-token") },
            )
            backend.signInFailure = null
            backend.failWith = HttpStatusCode.ServiceUnavailable
            assertEquals(
                SignInOutcome.NetworkUnavailable,
                coordinator.signIn { _, _ -> SignInCredential.GoogleIdToken("id-token") },
            )
            assertNull(tokens.tokens())
        }
}
