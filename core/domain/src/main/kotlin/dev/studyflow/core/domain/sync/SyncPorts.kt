package dev.studyflow.core.domain.sync

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * The remote half of sync: the two calls the protocol in `docs/api/openapi.yaml` offers.
 *
 * Kept as an interface in the domain so [SyncEngine] — the part with all the ordering rules — can
 * be driven by a plain in-memory fake in a unit test, and so the layer that knows about HTTP stays
 * outside the domain entirely (ADR 0002).
 */
public interface SyncTransport {
    /**
     * Sends one batch of local changes.
     *
     * Implementations must be safe to retry: a batch the server already applied has to be accepted
     * again unchanged, because a process death between "server wrote it" and "client acknowledged
     * it" is indistinguishable from a lost request.
     */
    public suspend fun push(records: List<SyncSessionRecord>): SyncResult<SyncPushAck>

    /**
     * Reads the changes recorded after [cursor], oldest first.
     *
     * @param cursor `null` on a first sync, which asks for the whole history.
     * @param limit maximum number of changes in the answer; the server may return fewer.
     */
    public suspend fun pull(
        cursor: String?,
        limit: Int,
    ): SyncResult<SyncPage>
}

/** The read-only slice of sync state the UI binds to. */
public interface SyncStatusRepository {
    public fun observeStatus(): Flow<SyncStatus>
}

/**
 * The local half of sync: the outbound queue, the inbound cursor, and the status the UI shows.
 *
 * Implementations must make [applyPage] atomic — every merged change *and* the cursor that covers
 * them, in one transaction. That is what makes the inbound delta resumable: a run killed halfway
 * either advanced the cursor with its page, or did neither and re-reads the same page next time.
 */
public interface SyncStore : SyncStatusRepository {
    /** The oldest [limit] queued changes, in insertion order. */
    public suspend fun pending(limit: Int): List<SyncQueueItem>

    /** The current local state of a queued session, tombstone included; `null` when it vanished. */
    public suspend fun sessionRecord(sessionId: String): SyncSessionRecord?

    /**
     * Removes queue entries the server has accepted.
     *
     * Addressed by [SyncQueueItem.sequence] rather than by entity id, so a mutation that re-queued
     * the same entity while the batch was in flight survives the acknowledgement.
     */
    public suspend fun acknowledge(sequences: List<Long>)

    /**
     * Merges one inbound page and advances the cursor, atomically.
     *
     * @return how many changes were stored; rejected ones (an active session, or a change the
     *   local copy beats outright) are not counted.
     */
    public suspend fun applyPage(page: SyncPage): Int

    /** The cursor the next pull resumes from; `null` before the first successful page. */
    public suspend fun cursor(): String?

    public suspend fun recordSuccess(at: Instant)

    public suspend fun recordFailure(
        failure: SyncFailure,
        at: Instant,
    )
}

/**
 * Asks the platform to run a sync.
 *
 * The UI and the repositories depend on this rather than on `WorkManager`, so "sync now" is one
 * call from a ViewModel and a unit test can assert it was made.
 */
public fun interface SyncScheduler {
    public suspend fun requestSync(trigger: SyncTrigger)
}
