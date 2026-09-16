package dev.studyflow.core.common.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injectable dispatchers.
 *
 * Hard-coding [Dispatchers] inside a use case makes it untestable without either a real thread pool
 * or a global override, so the choice is passed in instead.
 */
public interface DispatcherProvider {
    /** CPU-bound work: folding event logs, hashing, parsing. */
    public val default: CoroutineDispatcher

    /** Blocking work: disk, database, network. */
    public val io: CoroutineDispatcher

    /** UI thread. */
    public val main: CoroutineDispatcher
}

/** The production mapping onto [Dispatchers]. */
public object StandardDispatcherProvider : DispatcherProvider {
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val main: CoroutineDispatcher get() = Dispatchers.Main
}
