package dev.studyflow.core.domain.lifecycle

import dev.studyflow.core.domain.result.DomainError
import dev.studyflow.core.domain.result.DomainResult
import dev.studyflow.core.testing.coroutines.testDispatcherProvider
import dev.studyflow.core.testing.data.FakeRemoteAccountEraser
import dev.studyflow.core.testing.data.RecordingDataEraser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Account deletion")
class AccountDeletionCoordinatorTest {
    private val remote = FakeRemoteAccountEraser()
    private val database = RecordingDataEraser("database")
    private val settings = RecordingDataEraser("settings")
    private val tokens = RecordingDataEraser("credentials")

    @Test
    fun `the server is asked first, then every local store is cleared in order`() =
        runTest {
            val report = coordinator().deleteAccount(DeletionConfirmation(acknowledged = true)).valueOrFail()

            assertEquals(1, remote.calls)
            assertEquals(listOf("database", "settings", "credentials"), report.erased)
            assertTrue(report.isComplete)
        }

    @Test
    fun `nothing local is erased when the server refuses`() =
        runTest {
            remote.failWith(DomainError.Network)

            val result = coordinator().deleteAccount(DeletionConfirmation(acknowledged = true))

            assertEquals(DomainError.Network, (result as DomainResult.Failure).error)
            assertEquals(0, database.erasures, "a failed server delete must leave the device able to retry")
            assertEquals(0, tokens.erasures, "erasing the tokens would make the retry impossible")
        }

    @Test
    fun `an unconfirmed request never reaches the server`() =
        runTest {
            val result = coordinator().deleteAccount(DeletionConfirmation(acknowledged = false))

            assertEquals(DomainError.Validation, (result as DomainResult.Failure).error)
            assertEquals(0, remote.calls)
        }

    @Test
    fun `one store refusing does not strand the rest`() =
        runTest {
            val stubborn = RecordingDataEraser("thumbnails", failure = IOException("busy"))

            val report =
                coordinator(listOf(database, stubborn, tokens))
                    .deleteAccount(DeletionConfirmation(acknowledged = true))
                    .valueOrFail()

            assertEquals(listOf("database", "credentials"), report.erased)
            assertEquals(listOf("thumbnails"), report.failed)
            assertFalse(report.isComplete)
            assertEquals(1, tokens.erasures, "credentials are cleared even when an earlier store failed")
        }

    @Test
    fun `a device with no account signed in still erases its local data`() =
        runTest {
            remote.result = DomainResult.Success(AccountDeletionReceipt.LOCAL_ONLY)

            val report = coordinator().deleteAccount(DeletionConfirmation(acknowledged = true)).valueOrFail()

            assertEquals(0, report.receipt.retentionWindowDays)
            assertEquals(3, report.erased.size)
        }

    @Test
    fun `the retention window the server promised is reported to the user`() =
        runTest {
            val report = coordinator().deleteAccount(DeletionConfirmation(acknowledged = true)).valueOrFail()

            assertEquals(
                AccountDeletionReceipt.DEFAULT_RETENTION_WINDOW_DAYS,
                report.receipt.retentionWindowDays,
            )
        }

    @Test
    fun `a cache directory eraser removes the whole tree`(
        @TempDir cache: File,
    ) = runTest {
        File(cache, "thumbnails").mkdirs()
        File(cache, "thumbnails/material-1.webp").writeBytes(byteArrayOf(1))

        DirectoryEraser("caches") { cache }.erase()

        assertFalse(cache.exists())
    }

    @Test
    fun `erasing a directory that was never created succeeds`(
        @TempDir parent: File,
    ) = runTest {
        DirectoryEraser("caches") { File(parent, "never-created") }.erase()
    }

    private fun TestScope.coordinator(erasers: List<RecordingDataEraser> = listOf(database, settings, tokens)) =
        AccountDeletionCoordinator(
            remote = remote,
            erasers = erasers,
            dispatcherProvider = testDispatcherProvider(),
        )

    private fun <T> DomainResult<T>.valueOrFail(): T =
        when (this) {
            is DomainResult.Success -> value
            is DomainResult.Failure -> error("expected success but failed with $error")
        }
}
