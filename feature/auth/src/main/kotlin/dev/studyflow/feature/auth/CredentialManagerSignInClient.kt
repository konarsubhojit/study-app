package dev.studyflow.feature.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Obtains an assertion from the system Credential Manager. The caller sends the returned assertion
 * to its backend, which verifies it and exchanges it for [dev.studyflow.core.network.auth.AuthTokens].
 *
 * Two request shapes are used, because the pinned `credentials-play-services-auth` rejects a
 * [GetSignInWithGoogleOption] that shares a request with any other option
 * ("GetSignInWithGoogleOption cannot be combined with other options"):
 *
 * * With a passkey request, one sheet offers the passkey and Google together. Google is a
 *   [GetGoogleIdOption] there — the only Google option that may be combined — and if that sheet
 *   has nothing to offer, the client retries with Google alone.
 * * Google alone — the retry, and the whole flow while passkey sign-in is switched off — is a
 *   [GetSignInWithGoogleOption]. It always shows the account chooser, where a [GetGoogleIdOption]
 *   may drop itself from the sheet without raising anything.
 */
public class CredentialManagerSignInClient(
    private val credentialManager: CredentialManager,
) : SignInCredentialProvider {
    override suspend fun getCredential(
        activity: Activity,
        passkeyRequestJson: String?,
        google: GoogleSignInConfig,
    ): SignInCredential {
        require(passkeyRequestJson == null || passkeyRequestJson.isNotBlank()) {
            "passkeyRequestJson must be null or non-blank"
        }
        if (passkeyRequestJson == null) return signInWithGoogle(activity, google)

        val request =
            GetCredentialRequest(
                listOf(
                    GetPublicKeyCredentialOption(passkeyRequestJson),
                    GetGoogleIdOption
                        .Builder()
                        .setServerClientId(google.serverClientId)
                        .setFilterByAuthorizedAccounts(false)
                        .build(),
                ),
            )
        return try {
            credentialManager.getCredential(activity, request).credential.toSignInCredential()
        } catch (_: NoCredentialException) {
            signInWithGoogle(activity, google)
        }
    }

    private suspend fun signInWithGoogle(
        activity: Activity,
        google: GoogleSignInConfig,
    ): SignInCredential {
        val request = GetCredentialRequest(listOf(GetSignInWithGoogleOption.Builder(google.serverClientId).build()))
        return try {
            credentialManager.getCredential(activity, request).credential.toSignInCredential()
        } catch (stillNoCredential: NoCredentialException) {
            throw NoSignInCredentialAvailableException(stillNoCredential)
        }
    }

    public companion object {
        public fun create(activity: Activity): CredentialManagerSignInClient =
            CredentialManagerSignInClient(CredentialManager.create(activity))
    }
}

public fun interface SignInCredentialProvider {
    /** [passkeyRequestJson] is `null` when passkey sign-in is disabled; only Google is offered then. */
    public suspend fun getCredential(
        activity: Activity,
        passkeyRequestJson: String?,
        google: GoogleSignInConfig,
    ): SignInCredential
}

/** Thrown when the system Credential Manager has no passkey or Google account to offer. */
public class NoSignInCredentialAvailableException(
    cause: NoCredentialException,
) : Exception(cause)

/** A server-verifiable proof. Its contents must never be logged. */
public sealed interface SignInCredential {
    public class Passkey internal constructor(
        val authenticationResponseJson: String,
    ) : SignInCredential {
        override fun toString(): String = "Passkey(redacted)"
    }

    public class GoogleIdToken(
        val idToken: String,
    ) : SignInCredential {
        override fun toString(): String = "GoogleIdToken(redacted)"
    }
}

private fun androidx.credentials.Credential.toSignInCredential(): SignInCredential =
    when (this) {
        is PublicKeyCredential -> {
            SignInCredential.Passkey(authenticationResponseJson)
        }

        is CustomCredential
        if type in GOOGLE_ID_TOKEN_TYPES -> {
            SignInCredential.GoogleIdToken(GoogleIdTokenCredential.createFrom(data).idToken)
        }

        else -> {
            error("Unsupported Credential Manager credential type")
        }
    }

// A Play Services sign-in intent reports the plain ID-token type; a framework provider answering a
// Sign in with Google request may report the SIWG variant. Both carry the same bundle.
private val GOOGLE_ID_TOKEN_TYPES =
    setOf(
        GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL,
    )
