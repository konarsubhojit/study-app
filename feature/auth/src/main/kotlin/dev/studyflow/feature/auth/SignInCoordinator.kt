package dev.studyflow.feature.auth

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import dev.studyflow.core.network.ApiResult
import dev.studyflow.core.network.StudyFlowApi
import dev.studyflow.core.network.auth.AuthTokens
import dev.studyflow.core.network.error.ApiError
import dev.studyflow.core.network.model.SignInCredentialDto
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Provider

public enum class SignInOutcome {
    SignedIn,
    Cancelled,
    NoCredential,
    NetworkUnavailable,
    Rejected,
    Failed,
}

public class SignInCoordinator
    @Inject
    constructor(
        private val api: StudyFlowApi,
        private val session: AuthSession,
        private val google: Provider<GoogleSignInConfig>,
    ) {
        public suspend fun signIn(
            getCredential: suspend (String, GoogleSignInConfig) -> SignInCredential,
        ): SignInOutcome {
            val config =
                try {
                    google.get()
                } catch (_: IllegalStateException) {
                    null
                }
            return if (config == null) {
                SignInOutcome.Failed
            } else {
                when (val challenge = api.beginSignIn()) {
                    is ApiResult.Failure -> {
                        challenge.error.toSignInOutcome()
                    }

                    is ApiResult.Success -> {
                        when (val selection = selectCredential(challenge.value.requestJson, config, getCredential)) {
                            is CredentialSelection.Unavailable -> selection.outcome
                            is CredentialSelection.Selected -> exchange(selection.credential)
                        }
                    }
                }
            }
        }

        private suspend fun selectCredential(
            request: String,
            config: GoogleSignInConfig,
            getCredential: suspend (String, GoogleSignInConfig) -> SignInCredential,
        ): CredentialSelection =
            try {
                CredentialSelection.Selected(getCredential(request, config))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GetCredentialCancellationException) {
                CredentialSelection.Unavailable(SignInOutcome.Cancelled)
            } catch (_: NoSignInCredentialAvailableException) {
                CredentialSelection.Unavailable(SignInOutcome.NoCredential)
            } catch (_: GetCredentialException) {
                CredentialSelection.Unavailable(SignInOutcome.Failed)
            }

        private suspend fun exchange(credential: SignInCredential): SignInOutcome {
            val proof =
                when (credential) {
                    is SignInCredential.Passkey -> SignInCredentialDto.Passkey(credential.authenticationResponseJson)
                    is SignInCredential.GoogleIdToken -> SignInCredentialDto.Google(credential.idToken)
                }
            return when (val result = api.signIn(proof)) {
                is ApiResult.Success -> {
                    session.completeSignIn(AuthTokens(result.value.accessToken, result.value.refreshToken))
                    SignInOutcome.SignedIn
                }

                is ApiResult.Failure -> {
                    result.error.toSignInOutcome()
                }
            }
        }
    }

private sealed interface CredentialSelection {
    data class Selected(
        val credential: SignInCredential,
    ) : CredentialSelection

    data class Unavailable(
        val outcome: SignInOutcome,
    ) : CredentialSelection
}

private fun ApiError.toSignInOutcome(): SignInOutcome =
    when (this) {
        is ApiError.Offline, is ApiError.Timeout, is ApiError.Server -> SignInOutcome.NetworkUnavailable
        is ApiError.Unauthorized, is ApiError.Forbidden -> SignInOutcome.Rejected
        else -> SignInOutcome.Failed
    }
