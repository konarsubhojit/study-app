package dev.studyflow.core.domain.sync

import dev.studyflow.core.common.time.Clock

/**
 * The order in which sync does its two jobs, and the rules for when it does neither (issue #55).
 *
 * Deliberately free of Android, HTTP and Room: everything platform-shaped sits behind [SyncStore]
 * and [SyncTransport], so the parts that are easy to get wrong — batching, acknowledging,
 * resuming, and not spending a user's data on nothing — are exercised by plain JVM tests.
 *
 * **Outbound first, then inbound.** A local change is pushed before the inbound delta is read, so
 * a device never applies a remote copy of a session it is about to overwrite anyway, and the pull
 * that follows a push is what delivers the server's verdict on a conflict.
 *
 * **Nothing to do means no traffic.** A run triggered by a local mutation
 * ([SyncTrigger.OUTBOUND]) with an empty queue makes no request at all. A scheduled or manual run
 * spends exactly one conditional `GET` per stream (sessions, then tasks and materials) — the
 * protocol's minimum for "has anything changed?" — and stops there when the answer is "no",
 * without writing to the database.
 *
 * **Interruption is free.** Every page is merged and its cursor advanced in one [SyncStore]
 * transaction, so a run that is killed mid-delta resumes from the last page it *finished* rather
 * than re-reading the whole history or skipping a page.
 */
public class SyncEngine(
    private val store: SyncStore,
    private val transport: SyncTransport,
    private val clock: Clock,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val maxPagesPerRun: Int = DEFAULT_MAX_PAGES_PER_RUN,
) {
    init {
        require(batchSize > 0) { "batchSize must be positive, was $batchSize" }
        require(maxPagesPerRun > 0) { "maxPagesPerRun must be positive, was $maxPagesPerRun" }
    }

    /** Drains the outbound queue, then reads the inbound delta, recording what happened. */
    public suspend fun sync(trigger: SyncTrigger): SyncOutcome =
        when (val outbound = drainQueue()) {
            is DrainOutcome.Failed -> {
                fail(outbound.failure, SyncStage.PUSH)
            }

            is DrainOutcome.Drained -> {
                // A mutation asked for its own change to be sent; with nothing left to send there
                // is nothing to do, and polling the server on every local edit is exactly the data
                // use the acceptance criteria forbid.
                if (trigger == SyncTrigger.OUTBOUND && outbound.pushed == 0) {
                    SyncOutcome.Idle
                } else {
                    applyDelta(pushed = outbound.pushed, recordsPushed = outbound.recordsPushed)
                }
            }
        }

    private suspend fun applyDelta(
        pushed: Int,
        recordsPushed: Int,
    ): SyncOutcome =
        when (val inbound = readDelta()) {
            is PullOutcome.Failed -> {
                fail(inbound.failure, SyncStage.PULL)
            }

            is PullOutcome.Pulled -> {
                store.recordSuccess(clock.now())
                SyncOutcome.Synced(
                    pushed = pushed,
                    applied = inbound.applied,
                    recordsPushed = recordsPushed,
                    recordsPulled = inbound.received,
                    recordCursorAdvanced = inbound.cursorAdvanced,
                )
            }
        }

    @Suppress("ReturnCount") // Each stream's failure ends the drain before the batch is acknowledged.
    private suspend fun drainQueue(): DrainOutcome {
        var pushed = 0
        var recordsPushed = 0
        while (true) {
            val batch = store.pending(batchSize)
            if (batch.isEmpty()) return DrainOutcome.Drained(pushed, recordsPushed)

            val sessions =
                batch
                    .filter { it.entityType == SyncEntityType.SESSION }
                    .mapNotNull { item -> store.sessionRecord(item.entityId) }
            val documents =
                batch
                    .filter { it.entityType != SyncEntityType.SESSION }
                    .mapNotNull { item -> store.documentRecord(item) }
            // Counted as sent rather than as accepted: a batch the server rejected because it
            // holds something newer still costs a request, and the pull that follows is how this
            // device learns what that newer state is.
            if (sessions.isNotEmpty()) {
                val result = transport.push(sessions)
                if (result is SyncResult.Failure) return DrainOutcome.Failed(result.failure)
                pushed += sessions.size
            }
            if (documents.isNotEmpty()) {
                val result = transport.pushDocuments(documents)
                if (result is SyncResult.Failure) return DrainOutcome.Failed(result.failure)
                pushed += documents.size
                recordsPushed += documents.size
            }
            // Acknowledged by sequence: a mutation that re-queued one of these entities while the
            // batch was in flight holds a newer sequence and stays pending.
            store.acknowledge(batch.map(SyncQueueItem::sequence))
        }
    }

    @Suppress("ReturnCount") // Either stream's failure ends the pass.
    private suspend fun readDelta(): PullOutcome {
        val sessions =
            readStream(
                cursor = store::cursor,
                pull = transport::pull,
                apply = store::applyPage,
                describe = { page ->
                    PageShape(page.changes.isEmpty(), page.nextCursor, page.hasMore, page.changes.size)
                },
            )
        if (sessions is PullOutcome.Failed) return sessions
        val documents =
            readStream(
                cursor = store::documentCursor,
                pull = transport::pullDocuments,
                apply = { page -> store.applyDocumentPage(page, clock.now()) },
                describe = { page ->
                    PageShape(page.changes.isEmpty(), page.nextCursor, page.hasMore, page.changes.size)
                },
            )
        if (documents is PullOutcome.Failed) return documents
        val sessionPull = sessions as PullOutcome.Pulled
        val documentPull = documents as PullOutcome.Pulled
        return PullOutcome.Pulled(
            applied = sessionPull.applied + documentPull.applied,
            received = documentPull.received,
            cursorAdvanced = documentPull.cursorAdvanced,
        )
    }

    private suspend fun <P> readStream(
        cursor: suspend () -> String?,
        pull: suspend (String?, Int) -> SyncResult<P>,
        apply: suspend (P) -> Int,
        describe: (P) -> PageShape,
    ): PullOutcome {
        var applied = 0
        var received = 0
        var cursorAdvanced = false
        var pagesLeft = maxPagesPerRun
        var moreToRead = true
        while (moreToRead && pagesLeft-- > 0) {
            val from = cursor()
            val page =
                when (val result = pull(from, batchSize)) {
                    is SyncResult.Failure -> return PullOutcome.Failed(result.failure)
                    is SyncResult.Success -> result.value
                }
            val shape = describe(page)
            received += shape.count
            cursorAdvanced = cursorAdvanced || (shape.nextCursor != null && shape.nextCursor != from)

            // Nothing changed: no rows to merge and the same cursor to resume from, so there is
            // nothing worth opening a transaction for.
            val unchanged = shape.empty && shape.nextCursor == from
            if (!unchanged) applied += apply(page)

            // Stopping when the page budget runs out — rather than looping until the server says
            // it is done — is what keeps a busy server from holding a worker, and the user's
            // radio, open indefinitely; the next run resumes from the cursor.
            moreToRead = !unchanged && shape.hasMore
        }
        return PullOutcome.Pulled(applied, received, cursorAdvanced)
    }

    private data class PageShape(
        val empty: Boolean,
        val nextCursor: String?,
        val hasMore: Boolean,
        val count: Int,
    )

    private suspend fun fail(
        failure: SyncFailure,
        stage: SyncStage,
    ): SyncOutcome {
        store.recordFailure(failure, clock.now())
        return SyncOutcome.Failed(failure, stage)
    }

    private sealed interface DrainOutcome {
        data class Drained(
            val pushed: Int,
            val recordsPushed: Int,
        ) : DrainOutcome

        data class Failed(
            val failure: SyncFailure,
        ) : DrainOutcome
    }

    private sealed interface PullOutcome {
        data class Pulled(
            val applied: Int,
            val received: Int = 0,
            val cursorAdvanced: Boolean = false,
        ) : PullOutcome

        data class Failed(
            val failure: SyncFailure,
        ) : PullOutcome
    }

    public companion object {
        /** Small enough that one failed batch costs little, large enough to keep round trips down. */
        public const val DEFAULT_BATCH_SIZE: Int = 50

        /** A bound on one run, so a busy server cannot keep a worker alive forever. */
        public const val DEFAULT_MAX_PAGES_PER_RUN: Int = 100
    }
}
