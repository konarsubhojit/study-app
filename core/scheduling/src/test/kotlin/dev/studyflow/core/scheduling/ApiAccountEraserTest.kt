package dev.studyflow.core.scheduling

import dev.studyflow.core.domain.lifecycle.AccountDeletionReceipt
import dev.studyflow.core.domain.result.DomainError
import dev.studyflow.core.domain.result.DomainResult
import dev.studyflow.core.network.auth.AuthTokens
import dev.studyflow.core.network.auth.InMemoryTokenStore
import dev.studyflow.core.network.auth.TokenStore
import dev.studyflow.core.network.model.AccountDeletionReceiptDto
import dev.studyflow.core.testing.network.FakeStudyFlowBackend
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wire half of account deletion, exercised through the real client (issue #78).
 *
 * Going through [FakeStudyFlowBackend] rather than a stubbed `StudyFlowApi` keeps the test honest
 * about serialization and error mapping, which is where a "the account was deleted" claim would
 * otherwise be easiest to fake.
 */
class ApiAccountEraserTest {
    private val backend = FakeStudyFlowBackend()

    @Test
    fun `a signed-in account is deleted and the retention window is reported`() =
        runTest {
            backend.accountDeletionReceipt =
                AccountDeletionReceiptDto(acceptedAtIso = "2026-03-01T09:00:00Z", retentionWindowDays = 30)

            val result = eraser().deleteAccount()

            assertEquals(1, backend.acceptedAccountDeletions)
            assertEquals(
                DomainResult.Success(AccountDeletionReceipt("2026-03-01T09:00:00Z", 30)),
                result,
            )
        }

    @Test
    fun `a device with nobody signed in never calls the server`() =
        runTest {
            val result = eraser(tokenStore = InMemoryTokenStore()).deleteAccount()

            assertEquals("the server holds nothing for a local-only device", 0, backend.acceptedAccountDeletions)
            assertEquals(DomainResult.Success(AccountDeletionReceipt.LOCAL_ONLY), result)
        }

    @Test
    fun `an account the server no longer has counts as deleted`() =
        runTest {
            backend.failWith = HttpStatusCode.NotFound

            val result = eraser().deleteAccount()

            assertEquals(
                "a retry after a lost response must not strand the user",
                DomainResult.Success(AccountDeletionReceipt.LOCAL_ONLY),
                result,
            )
        }

    @Test
    fun `a server failure is reported so the local data survives for a retry`() =
        runTest {
            backend.failWith = HttpStatusCode.ServiceUnavailable

            val result = eraser().deleteAccount()

            assertEquals(DomainResult.Failure(DomainError.Network), result)
        }

    @Test
    fun `rejected credentials are a permission failure, not a network one`() =
        runTest {
            backend.failWith = HttpStatusCode.Forbidden

            val result = eraser().deleteAccount()

            assertEquals(DomainResult.Failure(DomainError.Permission), result)
        }

    private fun eraser(
        tokenStore: TokenStore = InMemoryTokenStore(AuthTokens("test-access", "test-refresh")),
    ): ApiAccountEraser = ApiAccountEraser(api = backend.api(tokenStore), tokenStore = tokenStore)
}
