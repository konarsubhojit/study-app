package dev.studyflow.core.network.retry

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Exponential backoff with jitter for retryable failures (issue #63).
 *
 * ### Why jitter, and why not retry everything
 *
 * Without jitter every phone that failed during the same server hiccup retries at the same
 * millisecond, and the herd knocks the recovering server over again. The delay is therefore a
 * random point inside the backoff window rather than the window's edge.
 *
 * Only failures that are plausibly transient are retried. Replaying a `409 Conflict` cannot help,
 * and replaying a non-idempotent request that already succeeded server-side would duplicate the
 * user's data — so retries are limited to idempotent methods and transient statuses.
 */
public data class RetryPolicy(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val baseDelay: Duration = 500.milliseconds,
    val maxDelay: Duration = 10.seconds,
    val jitterFactor: Double = DEFAULT_JITTER_FACTOR,
) {
    init {
        require(maxRetries >= 0) { "RetryPolicy.maxRetries must not be negative, was $maxRetries" }
        require(baseDelay > Duration.ZERO) { "RetryPolicy.baseDelay must be positive, was $baseDelay" }
        require(maxDelay >= baseDelay) { "RetryPolicy.maxDelay must be at least baseDelay" }
        require(jitterFactor in 0.0..1.0) {
            "RetryPolicy.jitterFactor must be within 0.0..1.0, was $jitterFactor"
        }
    }

    /**
     * How long to wait before retry number [attempt].
     *
     * @param attempt 1 for the first retry.
     * @param retryAfter the server's `Retry-After` in seconds; honoured when it is longer than the
     *   computed backoff, because the server knows better than the client when it will be ready.
     * @param random injected so the bounds can be asserted in a test rather than hoped for.
     */
    public fun delayFor(
        attempt: Int,
        retryAfter: Long? = null,
        random: Random = Random.Default,
    ): Duration {
        require(attempt >= 1) { "attempt must be at least 1, was $attempt" }

        val exponent = (attempt - 1).coerceAtMost(MAX_EXPONENT)
        val backoffMillis =
            (baseDelay.inWholeMilliseconds.toDouble() * (1 shl exponent))
                .coerceAtMost(maxDelay.inWholeMilliseconds.toDouble())
        val floorMillis = backoffMillis * (1.0 - jitterFactor)
        val jittered = floorMillis + random.nextDouble() * (backoffMillis - floorMillis)

        val serverRequested = retryAfter?.takeIf { it > 0 }?.seconds
        return maxOf(jittered.toLong().milliseconds, serverRequested ?: Duration.ZERO)
    }

    /** True when replaying the request could plausibly succeed and cannot duplicate user data. */
    public fun isRetryable(
        status: Int,
        idempotent: Boolean,
    ): Boolean = idempotent && (status in RETRYABLE_STATUSES || (status in SERVER_ERRORS && status != NOT_IMPLEMENTED))

    public companion object {
        private const val DEFAULT_MAX_RETRIES = 3
        private const val DEFAULT_JITTER_FACTOR = 0.5
        private const val MAX_EXPONENT = 16
        private const val NOT_IMPLEMENTED = 501
        private val RETRYABLE_STATUSES = setOf(408, 425, 429)
        private val SERVER_ERRORS = 500..599
    }
}
