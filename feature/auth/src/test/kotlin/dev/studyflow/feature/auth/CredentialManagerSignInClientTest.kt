package dev.studyflow.feature.auth

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.os.CancellationSignal
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CreateCredentialRequest
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PrepareGetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CredentialManagerSignInClientTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val google = GoogleSignInConfig("web-client-id")
    private val passkeyRequest = """{"challenge":"abc","rpId":"example.invalid"}"""

    @Test
    fun `passkey and Google share one sheet using the combinable Google option`() =
        runTest {
            val manager =
                ScriptedCredentialManager(googleIdToken(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL))

            val credential = CredentialManagerSignInClient(manager).getCredential(activity, passkeyRequest, google)

            assertEquals(ID_TOKEN, (credential as SignInCredential.GoogleIdToken).idToken)
            val options = manager.requests.single().credentialOptions
            assertEquals(2, options.size)
            assertTrue(options[0] is GetPublicKeyCredentialOption)
            val googleOption = options[1] as GetGoogleIdOption
            assertEquals("web-client-id", googleOption.serverClientId)
            assertFalse(googleOption.filterByAuthorizedAccounts)
        }

    @Test
    fun `a passkey assertion maps to a passkey credential`() =
        runTest {
            val manager = ScriptedCredentialManager(PublicKeyCredential("""{"id":"credential"}"""))

            val credential = CredentialManagerSignInClient(manager).getCredential(activity, passkeyRequest, google)

            assertEquals("""{"id":"credential"}""", (credential as SignInCredential.Passkey).authenticationResponseJson)
        }

    @Test
    fun `an empty combined sheet falls back to Sign in with Google on its own`() =
        runTest {
            val manager =
                ScriptedCredentialManager(
                    NoCredentialException(),
                    googleIdToken(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL),
                )

            val credential = CredentialManagerSignInClient(manager).getCredential(activity, passkeyRequest, google)

            assertEquals(ID_TOKEN, (credential as SignInCredential.GoogleIdToken).idToken)
            assertEquals(2, manager.requests.size)
            assertSignInWithGoogleOnly(manager.requests[1])
        }

    @Test
    fun `no Google account after the fallback is reported as no credential`() =
        runTest {
            val manager = ScriptedCredentialManager(NoCredentialException(), NoCredentialException())
            val client = CredentialManagerSignInClient(manager)

            val failure = runCatching { client.getCredential(activity, passkeyRequest, google) }.exceptionOrNull()

            assertTrue(failure.toString(), failure is NoSignInCredentialAvailableException)
            assertEquals(2, manager.requests.size)
        }

    @Test
    fun `disabled passkeys ask for Sign in with Google alone`() =
        runTest {
            val manager =
                ScriptedCredentialManager(googleIdToken(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL))

            val credential = CredentialManagerSignInClient(manager).getCredential(activity, null, google)

            assertEquals(ID_TOKEN, (credential as SignInCredential.GoogleIdToken).idToken)
            assertSignInWithGoogleOnly(manager.requests.single())
        }

    @Test
    fun `an unrelated custom credential is refused`() =
        runTest {
            val manager = ScriptedCredentialManager(CustomCredential("com.example.OTHER", android.os.Bundle()))
            val client = CredentialManagerSignInClient(manager)

            val failure = runCatching { client.getCredential(activity, null, google) }.exceptionOrNull()

            assertTrue(failure.toString(), failure is IllegalStateException)
        }

    @Test
    fun `mapped credentials keep their redacting toString`() =
        runTest {
            val manager =
                ScriptedCredentialManager(googleIdToken(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL))

            val credential = CredentialManagerSignInClient(manager).getCredential(activity, null, google)

            assertEquals("GoogleIdToken(redacted)", credential.toString())
            assertFalse(credential.toString().contains(ID_TOKEN))
        }

    private fun assertSignInWithGoogleOnly(request: GetCredentialRequest) {
        val option = request.credentialOptions.single() as GetSignInWithGoogleOption
        assertEquals("web-client-id", option.serverClientId)
    }

    private fun googleIdToken(type: String): Credential {
        val token =
            GoogleIdTokenCredential
                .Builder()
                .setId("student@example.invalid")
                .setIdToken(ID_TOKEN)
                .build()
        return if (type == token.type) token else CustomCredential(type, token.data)
    }
}

// googleid parses the token's JWT shape and claims but not its signature; this is an unsigned placeholder.
private val ID_TOKEN =
    listOf("""{"alg":"none"}""", """{"sub":"test-subject"}""", "signature").joinToString(".") {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray())
    }

/** Answers each `getCredential` call with the next scripted credential, or throws it. */
private class ScriptedCredentialManager(
    vararg responses: Any,
) : CredentialManager {
    private val script = ArrayDeque(responses.toList())
    val requests = mutableListOf<GetCredentialRequest>()

    override suspend fun getCredential(
        context: Context,
        request: GetCredentialRequest,
    ): GetCredentialResponse {
        requests += request
        return when (val next = script.removeFirst()) {
            is GetCredentialException -> throw next
            is Credential -> GetCredentialResponse(next)
            else -> error("Unscripted response $next")
        }
    }

    override fun getCredentialAsync(
        context: Context,
        request: GetCredentialRequest,
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: CredentialManagerCallback<GetCredentialResponse, GetCredentialException>,
    ): Unit = unused()

    override fun getCredentialAsync(
        context: Context,
        pendingGetCredentialHandle: PrepareGetCredentialResponse.PendingGetCredentialHandle,
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: CredentialManagerCallback<GetCredentialResponse, GetCredentialException>,
    ): Unit = unused()

    override fun prepareGetCredentialAsync(
        request: GetCredentialRequest,
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: CredentialManagerCallback<PrepareGetCredentialResponse, GetCredentialException>,
    ): Unit = unused()

    override fun createCredentialAsync(
        context: Context,
        request: CreateCredentialRequest,
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: CredentialManagerCallback<CreateCredentialResponse, CreateCredentialException>,
    ): Unit = unused()

    override fun clearCredentialStateAsync(
        request: ClearCredentialStateRequest,
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: CredentialManagerCallback<Void?, ClearCredentialException>,
    ): Unit = unused()

    override fun createSettingsPendingIntent(): PendingIntent = unused()

    private fun unused(): Nothing = error("Not used by CredentialManagerSignInClient")
}
