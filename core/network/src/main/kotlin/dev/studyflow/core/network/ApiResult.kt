package dev.studyflow.core.network

import dev.studyflow.core.network.error.ApiError

/**
 * The result of an API call: a value, or an error the UI already knows how to explain (issue #63).
 *
 * Returning this rather than throwing is what makes error handling checkable — the compiler
 * reminds a caller that a failure exists, and `when` over [ApiError] is exhaustive.
 */
public sealed interface ApiResult<out T> {
    public data class Success<T>(
        val value: T,
    ) : ApiResult<T>

    public data class Failure(
        val error: ApiError,
    ) : ApiResult<Nothing>

    /** The value, or `null` when the call failed. */
    public fun valueOrNull(): T? = (this as? Success)?.value

    /** The error, or `null` when the call succeeded. */
    public fun errorOrNull(): ApiError? = (this as? Failure)?.error
}

/** Transforms a successful value, leaving a failure untouched. */
public inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> =
    when (this) {
        is ApiResult.Success -> ApiResult.Success(transform(value))
        is ApiResult.Failure -> this
    }

/** Collapses both branches into one value, typically a UI state. */
public inline fun <T, R> ApiResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (ApiError) -> R,
): R =
    when (this) {
        is ApiResult.Success -> onSuccess(value)
        is ApiResult.Failure -> onFailure(error)
    }
