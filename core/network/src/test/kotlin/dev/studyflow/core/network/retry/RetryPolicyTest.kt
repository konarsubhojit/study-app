package dev.studyflow.core.network.retry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@DisplayName("RetryPolicy")
class RetryPolicyTest {
    private val policy = RetryPolicy(maxRetries = 3, baseDelay = 100.milliseconds, maxDelay = 2.seconds)

    @Test
    fun `backoff grows with each attempt`() {
        val noJitter = policy.copy(jitterFactor = 0.0)

        assertEquals(100.milliseconds, noJitter.delayFor(attempt = 1))
        assertEquals(200.milliseconds, noJitter.delayFor(attempt = 2))
        assertEquals(400.milliseconds, noJitter.delayFor(attempt = 3))
    }

    @Test
    fun `backoff is capped so a retry never waits minutes`() {
        val noJitter = policy.copy(jitterFactor = 0.0)

        assertEquals(2.seconds, noJitter.delayFor(attempt = 20))
    }

    @Test
    fun `jitter keeps every delay inside its window, so clients do not retry in lockstep`() {
        val random = Random(seed = 1)
        val delays = (1..200).map { policy.delayFor(attempt = 2, random = random) }

        // Window for attempt 2: 200ms backoff, halved by the default jitter factor.
        assertTrue(delays.all { it in 100.milliseconds..200.milliseconds }, "outside window: $delays")
        assertTrue(delays.distinct().size > 1, "jitter produced a constant delay")
    }

    @Test
    fun `the server's Retry-After wins when it asks for longer`() {
        assertEquals(5.seconds, policy.delayFor(attempt = 1, retryAfter = 5))
    }

    @Test
    fun `a Retry-After shorter than the backoff does not shorten it`() {
        val noJitter = policy.copy(baseDelay = 4.seconds, maxDelay = 8.seconds, jitterFactor = 0.0)

        assertEquals(4.seconds, noJitter.delayFor(attempt = 1, retryAfter = 1))
    }

    @Test
    fun `transient failures are retried`() {
        listOf(408, 425, 429, 500, 502, 503, 504).forEach { status ->
            assertTrue(policy.isRetryable(status, idempotent = true), "expected $status to be retryable")
        }
    }

    @Test
    fun `failures a retry cannot fix are not retried`() {
        listOf(400, 401, 403, 404, 409, 410, 426, 501).forEach { status ->
            assertFalse(policy.isRetryable(status, idempotent = true), "expected $status not to be retryable")
        }
    }

    @Test
    fun `a non-idempotent request is never replayed`() {
        assertFalse(policy.isRetryable(503, idempotent = false))
    }
}
