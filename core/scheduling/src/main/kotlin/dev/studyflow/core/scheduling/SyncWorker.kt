package dev.studyflow.core.scheduling

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.common.logging.DiagnosticKey
import dev.studyflow.core.common.logging.diagnosticEvent
import dev.studyflow.core.domain.sync.SyncEngine
import dev.studyflow.core.domain.sync.SyncOutcome
import dev.studyflow.core.domain.sync.SyncTrigger
import dev.studyflow.core.network.auth.AuthState
import dev.studyflow.core.network.auth.TokenStore
import kotlinx.coroutines.CancellationException

/** The trigger a sync work request carries in its input data, as a [SyncTrigger] name. */
public const val EXTRA_SYNC_TRIGGER: String = "dev.studyflow.core.scheduling.SYNC_TRIGGER"

/**
 * Told when a sync pass wrote other devices' changes into this one.
 *
 * Replicated tasks arrive with reminders this device has never registered with the platform, so
 * production reconciles reminder scheduling here; a pull that changed nothing does not.
 */
public fun interface RemoteChangesListener {
    public suspend fun onRemoteChangesApplied()
}

/**
 * Runs one sync pass under WorkManager's constraints and backoff (issue #55).
 *
 * Deliberately thin: all of the ordering, batching and resumption rules live in [SyncEngine], which
 * is testable without Android. What belongs here is the platform translation — reading why the run
 * was asked for, and turning the outcome into the [Result] WorkManager acts on.
 *
 * A retryable failure returns [Result.retry] so the exponential backoff configured by
 * [WorkManagerSyncCoordinator] applies; a permanent one returns [Result.failure], because retrying
 * a request the server will keep rejecting only costs the user battery. Neither loses data: the
 * queue is only acknowledged on acceptance, so whatever did not go out is still there for the next
 * run.
 *
 * A local-only user has nothing to sync with, so a run without a signed-in account succeeds
 * without touching the network rather than recording an authorisation failure the user never
 * caused.
 */
@HiltWorker
public class SyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted parameters: WorkerParameters,
        private val syncEngine: SyncEngine,
        private val tokenStore: TokenStore,
        private val remoteChangesListener: RemoteChangesListener,
        private val storageAudit: MaterialStorageAudit,
        private val logger: AppLogger,
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            val trigger =
                inputData.getString(EXTRA_SYNC_TRIGGER)?.let { name ->
                    runCatching { SyncTrigger.valueOf(name) }.getOrNull()
                } ?: SyncTrigger.SCHEDULED

            if (tokenStore.authState.value != AuthState.SignedIn) {
                // Not a failure the user caused, so nothing is recorded for the UI — but a bug
                // report must still be able to tell "skipped before any network call" from "ran
                // and was idle", which is the difference a queue that never empties hinges on.
                logger.diagnostic(
                    diagnosticEvent(DiagnosticCode.SyncSkippedSignedOut) {
                        put(DiagnosticKey.Trigger, trigger)
                    },
                )
                logger.info(TAG, "Sync skipped: no signed-in account")
                return Result.success()
            }

            val outcome =
                try {
                    syncEngine.sync(trigger)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (
                    @Suppress("TooGenericExceptionCaught") failure: Throwable,
                ) {
                    // A pass that threw is a bug, not a transport fault, and WorkManager would
                    // otherwise report only that the worker stopped. Naming the type is the whole
                    // difference between a diagnosable report and "it retried after three
                    // milliseconds".
                    logger.diagnostic(
                        diagnosticEvent(DiagnosticCode.SyncCrashed) {
                            put(DiagnosticKey.Trigger, trigger)
                        },
                        failure,
                    )
                    throw failure
                }

            return when (outcome) {
                SyncOutcome.Idle -> {
                    Result.success()
                }

                is SyncOutcome.Synced -> {
                    logger.diagnostic(
                        diagnosticEvent(DiagnosticCode.RecordSync) {
                            put(DiagnosticKey.Pushed, outcome.recordsPushed)
                            put(DiagnosticKey.Pulled, outcome.recordsPulled)
                            put(DiagnosticKey.CursorAdvanced, outcome.recordCursorAdvanced)
                        },
                    )
                    if (outcome.applied > 0) remoteChangesListener.onRemoteChangesApplied()
                    // A sync pass is the one moment this app is already talking to the backend
                    // about materials, so it is where a row that claims to be stored is checked
                    // against the store that is supposed to hold it (issue #193).
                    storageAudit.verifyStoredMaterials()
                    Result.success()
                }

                is SyncOutcome.Failed -> {
                    logger.diagnostic(
                        diagnosticEvent(DiagnosticCode.SyncFailed) {
                            put(DiagnosticKey.Trigger, trigger)
                            put(DiagnosticKey.Stage, outcome.stage)
                            put(DiagnosticKey.Reason, outcome.failure.reason)
                            put(DiagnosticKey.Retryable, outcome.failure.retryable)
                        },
                    )
                    logger.warning(TAG, "Sync failed: ${outcome.failure.message}")
                    if (outcome.failure.retryable) Result.retry() else Result.failure()
                }
            }
        }

        private companion object {
            const val TAG = "SyncWorker"
        }
    }
