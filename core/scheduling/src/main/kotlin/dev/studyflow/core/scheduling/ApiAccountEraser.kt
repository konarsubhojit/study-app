package dev.studyflow.core.scheduling

import dev.studyflow.core.domain.lifecycle.AccountDeletionReceipt
import dev.studyflow.core.domain.lifecycle.RemoteAccountEraser
import dev.studyflow.core.domain.result.DomainError
import dev.studyflow.core.domain.result.DomainResult
import dev.studyflow.core.network.ApiResult
import dev.studyflow.core.network.StudyFlowApi
import dev.studyflow.core.network.auth.AuthState
import dev.studyflow.core.network.auth.TokenStore
import dev.studyflow.core.network.error.ApiError

/**
 * The HTTP half of account deletion: `DELETE /v1/account` (issue #78).
 *
 * Lives beside [ApiSyncTransport] for the same reason it does — it speaks the domain's vocabulary
 * on one side and the wire's on the other, and neither `:core:network` (which knows no domain
 * types) nor `:core:domain` (which knows no HTTP) may hold that seam.
 *
 * Two server answers are deliberately treated as success rather than as failure:
 *
 * - **Nobody is signed in.** There is no account to delete, but the user's local data is still
 *   theirs to erase, so the flow continues with [AccountDeletionReceipt.LOCAL_ONLY].
 * - **The account is already gone** (`404`). A retry after a request whose answer never arrived
 *   must not strand the user with data they cannot delete.
 */
public class ApiAccountEraser(
    private val api: StudyFlowApi,
    private val tokenStore: TokenStore,
) : RemoteAccountEraser {
    override suspend fun deleteAccount(): DomainResult<AccountDeletionReceipt> {
        if (tokenStore.authState.value != AuthState.SignedIn) {
            return DomainResult.Success(AccountDeletionReceipt.LOCAL_ONLY)
        }

        return when (val result = api.deleteAccount()) {
            is ApiResult.Success -> {
                DomainResult.Success(
                    AccountDeletionReceipt(
                        acceptedAt = result.value.acceptedAtIso,
                        retentionWindowDays = result.value.retentionWindowDays,
                    ),
                )
            }

            is ApiResult.Failure -> {
                when (result.error) {
                    is ApiError.NotFound -> DomainResult.Success(AccountDeletionReceipt.LOCAL_ONLY)
                    else -> DomainResult.Failure(result.error.asDomainError())
                }
            }
        }
    }

    private fun ApiError.asDomainError(): DomainError =
        when (this) {
            is ApiError.Offline, is ApiError.Timeout, is ApiError.Server, is ApiError.RateLimited -> DomainError.Network
            is ApiError.Unauthorized, is ApiError.Forbidden -> DomainError.Permission
            else -> DomainError.Unknown
        }
}
