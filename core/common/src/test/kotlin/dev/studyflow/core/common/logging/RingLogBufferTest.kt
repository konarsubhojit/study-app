package dev.studyflow.core.common.logging

import dev.studyflow.core.common.time.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Instant

class RingLogBufferTest {
    private val ticks = AtomicLong()
    private val clock = Clock { Instant.fromEpochMilliseconds(ticks.incrementAndGet()) }

    @Test
    fun `keeps entries in order until it is full`() {
        val buffer = RingLogBuffer(clock = clock, capacity = 3)

        buffer.record(LogLevel.Info, "Sync", "code=SyncSkippedSignedOut")
        buffer.record(LogLevel.Warning, "Sync", "code=SyncFailed")

        assertEquals(
            listOf("code=SyncSkippedSignedOut", "code=SyncFailed"),
            buffer.snapshot().map(LogEntry::message),
        )
    }

    @Test
    fun `evicts the oldest entry rather than growing`() {
        val buffer = RingLogBuffer(clock = clock, capacity = 2)

        repeat(5) { index -> buffer.record(LogLevel.Info, "Sync", "entry$index") }

        assertEquals(listOf("entry3", "entry4"), buffer.snapshot().map(LogEntry::message))
    }

    @Test
    fun `a snapshot is not affected by later writes`() {
        val buffer = RingLogBuffer(clock = clock, capacity = 2)
        buffer.record(LogLevel.Info, "Sync", "first")

        val snapshot = buffer.snapshot()
        buffer.record(LogLevel.Info, "Sync", "second")

        assertEquals(listOf("first"), snapshot.map(LogEntry::message))
    }

    @Test
    fun `clearing leaves nothing to export`() {
        val buffer = RingLogBuffer(clock = clock, capacity = 4)
        buffer.record(LogLevel.Warning, "Sync", "code=SyncFailed")

        buffer.clear()

        assertTrue(buffer.snapshot().isEmpty(), "sign-out must not leave the previous account's log")
    }

    @Test
    fun `concurrent writers never exceed the capacity or lose the lock`() {
        val capacity = 16
        val buffer = RingLogBuffer(clock = clock, capacity = capacity)
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { thread ->
            pool.execute {
                start.await()
                repeat(perThread) { index -> buffer.record(LogLevel.Info, "Sync", "t$thread-$index") }
                buffer.snapshot()
                done.countDown()
            }
        }
        start.countDown()
        val finished = done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        pool.shutdown()

        assertTrue(finished, "writers deadlocked or never finished")
        assertEquals(capacity, buffer.snapshot().size)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
