package dev.studyflow.feature.auth

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import dev.studyflow.core.common.logging.AppLogger
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
        private val passkeys: PasskeySignInConfig,
        private val logger: AppLogger,
    ) {
        /**
         * [getCredential] receives the passkey request JSON, or `null` while passkey sign-in is
         * disabled, in which case no challenge is requested and only Google is offered.
         */
        public suspend fun signIn(
            getCredential: suspend (String?, GoogleSignInConfig) -> SignInCredential,
        ): SignInOutcome {
            val config =
                try {
                    google.get()
                } catch (unconfigured: IllegalStateException) {
                    // An empty GOOGLE_SERVER_CLIENT_ID would otherwise surface only as the generic
                    // "could not be completed" message; the exception names the missing property.
                    logger.warning(
                        TAG,
                        "Google sign-in is not configured; the Google option cannot be offered",
                        unconfigured,
                    )
                    null
                }
            return when {
                config == null -> {
                    SignInOutcome.Failed
                }

                !passkeys.enabled -> {
                    completeWith(selectCredential(null, config, getCredential))
                }

                else -> {
                    when (val challenge = api.beginSignIn()) {
                        is ApiResult.Failure -> {
                            challenge.error.toSignInOutcome()
                        }

                        is ApiResult.Success -> {
                            completeWith(
                                selectCredential(challenge.value.requestJson, config, getCredential),
                            )
                        }
                    }
                }
            }
        }

        private suspend fun completeWith(selection: CredentialSelection): SignInOutcome =
            when (selection) {
                is CredentialSelection.Unavailable -> selection.outcome
                is CredentialSelection.Selected -> exchange(selection.credential)
            }

        private suspend fun selectCredential(
            request: String?,
            config: GoogleSignInConfig,
            getCredential: suspend (String?, GoogleSignInConfig) -> SignInCredential,
        ): CredentialSelection =
            try {
                val credential = getCredential(request, config)
                // Only the kind of credential is logged, never its contents.
                logger.debug(TAG, "Credential selected: ${credential.kind}")
                CredentialSelection.Selected(credential)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GetCredentialCancellationException) {
                CredentialSelection.Unavailable(SignInOutcome.Cancelled)
            } catch (_: NoSignInCredentialAvailableException) {
                logger.warning(TAG, "Credential Manager offered neither a passkey nor a Google account")
                CredentialSelection.Unavailable(SignInOutcome.NoCredential)
            } catch (failure: GetCredentialException) {
                logger.warning(TAG, "Credential Manager request failed: ${failure.type}")
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

private const val TAG = "SignIn"

private val SignInCredential.kind: String
    get() =
        when (this) {
            is SignInCredential.Passkey -> "passkey"
            is SignInCredential.GoogleIdToken -> "google"
        }
