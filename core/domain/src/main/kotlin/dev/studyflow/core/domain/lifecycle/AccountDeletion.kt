package dev.studyflow.core.domain.lifecycle

import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.domain.result.DomainError
import dev.studyflow.core.domain.result.DomainResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What a deletion managed to remove.
 *
 * @property erased names of the stores that were cleared, in the order they were cleared.
 * @property failed stores that refused. A non-empty list does *not* mean the account survived:
 *   the server has already accepted the deletion by this point, and the user is told which local
 *   remnant needs an app-data clear.
 */
public data class AccountDeletionReport(
    val receipt: AccountDeletionReceipt,
    val erased: List<String>,
    val failed: List<String>,
) {
    public val isComplete: Boolean get() = failed.isEmpty()
}

/** Proof that the user was asked, and answered, before anything irreversible happened. */
public data class DeletionConfirmation(
    val acknowledged: Boolean,
)

/**
 * Deletes the account and everything the device keeps for it (issue #78).
 *
 * The ordering is the whole design, and it is deliberate in both directions:
 *
 * 1. **The server goes first.** A local wipe destroys the tokens the deletion request is
 *    authenticated with, so wiping first and failing on the network would leave an account nobody
 *    can ask to delete any more — the exact opposite of what the user asked for. If the server
 *    refuses, *nothing local is touched* and the user can retry.
 * 2. **Local erasure then runs to completion, even if a step fails.** Once the server has accepted,
 *    the account is gone; stopping halfway would leave the device holding orphaned study data and
 *    a token for an account that no longer exists. Every eraser is attempted and the failures are
 *    reported.
 * 3. **Credentials are erased last.** They are what an intermediate retry would need.
 *
 * A device with no account signed in skips step 1 (see [RemoteAccountEraser]) and erases local
 * data exactly the same way, so "delete my data" means the same thing signed in or not.
 */
public class AccountDeletionCoordinator(
    private val remote: RemoteAccountEraser,
    private val erasers: List<DataEraser>,
    private val dispatcherProvider: DispatcherProvider,
) {
    /**
     * Deletes the account, after the UI has confirmed it with the user.
     *
     * @return the report, or [DomainError.Validation] when the flow was not confirmed and
     *   the server's error when it refused the request.
     */
    public suspend fun deleteAccount(confirmation: DeletionConfirmation): DomainResult<AccountDeletionReport> =
        withContext(dispatcherProvider.io) {
            if (!confirmation.acknowledged) {
                return@withContext DomainResult.Failure(DomainError.Validation)
            }

            when (val remoteResult = remote.deleteAccount()) {
                is DomainResult.Failure -> remoteResult
                is DomainResult.Success -> DomainResult.Success(eraseLocally(remoteResult.value))
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun eraseLocally(receipt: AccountDeletionReceipt): AccountDeletionReport {
        val erased = mutableListOf<String>()
        val failed = mutableListOf<String>()

        erasers.forEach { eraser ->
            try {
                eraser.erase()
                erased += eraser.name
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The store's own exception is deliberately dropped: it can carry a path or a
                // provider message, and a deletion report is the last place a file name should
                // appear. The name of the store is what the user can act on.
                failed += eraser.name
            }
        }
        return AccountDeletionReport(receipt = receipt, erased = erased, failed = failed)
    }
}

/**
 * Erases a directory the app can rebuild — caches, thumbnails, staged material files.
 *
 * Lives in the domain rather than in the Android layer because there is nothing Android about
 * "delete this tree"; the platform only supplies *which* directory, which is what keeps the DI
 * wiring a one-liner per cache and this behaviour unit-testable on a temporary folder.
 */
public class DirectoryEraser(
    override val name: String,
    private val directory: () -> File,
) : DataEraser {
    override suspend fun erase() {
        val root = directory()
        if (!root.exists()) return
        check(root.deleteRecursively()) { "could not erase $name" }
    }
}
