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
                    return SignInOutcome.Failed
                }
            val challenge =
                when (val result = api.beginSignIn()) {
                    is ApiResult.Success -> result.value
                    is ApiResult.Failure -> return result.error.toSignInOutcome()
                }
            val credential =
                try {
                    getCredential(challenge.requestJson, config)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: GetCredentialCancellationException) {
                    return SignInOutcome.Cancelled
                } catch (_: NoSignInCredentialAvailableException) {
                    return SignInOutcome.NoCredential
                } catch (_: GetCredentialException) {
                    return SignInOutcome.Failed
                }
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

private fun ApiError.toSignInOutcome(): SignInOutcome =
    when (this) {
        is ApiError.Offline, is ApiError.Timeout, is ApiError.Server -> SignInOutcome.NetworkUnavailable
        is ApiError.Unauthorized, is ApiError.Forbidden -> SignInOutcome.Rejected
        else -> SignInOutcome.Failed
    }
