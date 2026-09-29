package dev.studyflow.feature.auth

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import dev.studyflow.core.common.logging.LogLevel
import dev.studyflow.core.network.auth.AuthState
import dev.studyflow.core.network.auth.InMemoryTokenStore
import dev.studyflow.core.network.model.SignInCredentialDto
import dev.studyflow.core.testing.logging.RecordingAppLogger
import dev.studyflow.core.testing.network.FakeStudyFlowBackend
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.inject.Provider

class SignInCoordinatorTest {
    private val backend = FakeStudyFlowBackend()
    private val tokens = InMemoryTokenStore()
    private val logger = RecordingAppLogger()
    private val coordinator = coordinator(passkeysEnabled = true)

    private fun coordinator(
        passkeysEnabled: Boolean,
        google: Provider<GoogleSignInConfig> = Provider { GoogleSignInConfig("web-client-id") },
    ) = SignInCoordinator(
        backend.api(tokens),
        AuthSession(tokens),
        google,
        PasskeySignInConfig(passkeysEnabled),
        logger,
    )

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
            val unconfigured = coordinator(passkeysEnabled = true, google = Provider { error("Missing web client id") })

            assertEquals(SignInOutcome.Failed, unconfigured.signIn { _, _ -> SignInCredential.GoogleIdToken("unused") })
            assertEquals(AuthState.LocalOnly, tokens.authState.value)
            assertEquals(emptyList<String>(), backend.requestedPaths)
            val warning = logger.messages.single { it.level == LogLevel.Warning }
            assertTrue(warning.message.contains("not configured"), warning.message)
            assertEquals("Missing web client id", warning.throwable?.message)
        }

    @Test
    fun `disabled passkeys skip the challenge and ask for Google only`() =
        runTest {
            val result =
                coordinator(passkeysEnabled = false).signIn { request, config ->
                    assertNull(request)
                    assertEquals("web-client-id", config.serverClientId)
                    SignInCredential.GoogleIdToken("id-token")
                }

            assertEquals(SignInOutcome.SignedIn, result)
            assertFalse(
                backend.requestedPaths.any { it.endsWith("/signin/challenge") },
                backend.requestedPaths.toString(),
            )
            assertEquals(listOf(SignInCredentialDto.Google("id-token")), backend.receivedSignInCredentials)
        }

    @Test
    fun `enabled passkeys request a challenge before asking for a credential`() =
        runTest {
            coordinator.signIn { request, _ ->
                assertEquals(backend.signInChallenge.requestJson, request)
                SignInCredential.Passkey("assertion")
            }

            assertTrue(
                backend.requestedPaths.any { it.endsWith("/signin/challenge") },
                backend.requestedPaths.toString(),
            )
        }

    @Test
    fun `diagnostics name the credential kind but never its contents`() =
        runTest {
            coordinator.signIn { _, _ -> SignInCredential.GoogleIdToken("secret-id-token") }
            coordinator.signIn { _, _ -> SignInCredential.Passkey("secret-assertion") }

            assertEquals(
                listOf("Credential selected: google", "Credential selected: passkey"),
                logger.messageTextsAt(LogLevel.Debug),
            )
            assertTrue(logger.messages.none { "secret" in it.message }, logger.messages.toString())
        }

    @Test
    fun `a Credential Manager failure is logged by type and reported as failed`() =
        runTest {
            val failure = GetCredentialUnknownException("details")
            val result = coordinator.signIn { _, _ -> throw failure }

            assertEquals(SignInOutcome.Failed, result)
            assertEquals(
                listOf("Credential Manager request failed: ${failure.type}"),
                logger.messageTextsAt(LogLevel.Warning),
            )
        }

    @Test
    fun `sign-in credentials redact their contents`() {
        assertEquals("GoogleIdToken(redacted)", SignInCredential.GoogleIdToken("id-token").toString())
        assertEquals("Passkey(redacted)", SignInCredential.Passkey("assertion").toString())
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
