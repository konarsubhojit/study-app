package dev.studyflow.feature.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Obtains an assertion from the system Credential Manager. The caller sends the returned assertion
 * to its backend, which verifies it and exchanges it for [dev.studyflow.core.network.auth.AuthTokens].
 */
public class CredentialManagerSignInClient(
    private val credentialManager: CredentialManager,
) : SignInCredentialProvider {
    override suspend fun getCredential(
        activity: Activity,
        passkeyRequestJson: String,
        google: GoogleSignInConfig,
    ): SignInCredential {
        require(passkeyRequestJson.isNotBlank()) { "passkeyRequestJson must not be blank" }

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
            try {
                val googleRequest =
                    GetCredentialRequest(
                        listOf(
                            GetGoogleIdOption
                                .Builder()
                                .setServerClientId(google.serverClientId)
                                .setFilterByAuthorizedAccounts(false)
                                .build(),
                        ),
                    )
                credentialManager.getCredential(activity, googleRequest).credential.toSignInCredential()
            } catch (stillNoCredential: NoCredentialException) {
                throw NoSignInCredentialAvailableException(stillNoCredential)
            }
        }
    }

    public companion object {
        public fun create(activity: Activity): CredentialManagerSignInClient =
            CredentialManagerSignInClient(CredentialManager.create(activity))
    }
}

public fun interface SignInCredentialProvider {
    public suspend fun getCredential(
        activity: Activity,
        passkeyRequestJson: String,
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
        if type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
            SignInCredential.GoogleIdToken(GoogleIdTokenCredential.createFrom(data).idToken)
        }

        else -> {
            error("Unsupported Credential Manager credential type")
        }
    }
