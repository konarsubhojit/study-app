package dev.studyflow.core.storage

/**
 * The failures an [ObjectStore] is allowed to have (issue #36).
 *
 * Callers — an upload worker deciding whether to reschedule, a UI deciding what to tell the user —
 * need "will this work if I try again?" answered by the type system rather than by inspecting a
 * message or an HTTP status, so [retryable] is part of the contract and `when` over the subclasses
 * is exhaustive.
 */
public sealed class ObjectStoreException(
    message: String,
    cause: Throwable? = null,
    /** Whether repeating the same call later could plausibly succeed. */
    public val retryable: Boolean,
) : Exception(message, cause) {
    /** The store holds nothing under this key — it was never uploaded, or it has been deleted. */
    public class NotFound(
        public val key: ObjectKey,
    ) : ObjectStoreException("no object stored under '$key'", retryable = false)

    /**
     * The request was refused: the presigned URL expired, or it was never ours to use.
     *
     * Not retryable *with this URL*; the caller's recovery is to ask the BFF for a fresh one.
     */
    public class AccessDenied(
        message: String,
        cause: Throwable? = null,
    ) : ObjectStoreException(message, cause, retryable = false)

    /** The stored bytes do not match the digest the device computed. Resending cannot fix that. */
    public class Integrity(
        message: String,
    ) : ObjectStoreException(message, retryable = false)

    /** The user is out of storage allowance. Retrying wastes battery until they free something. */
    public class QuotaExceeded(
        message: String,
    ) : ObjectStoreException(message, retryable = false)

    /** A dropped connection, a timeout, a 503 — the request is worth repeating. */
    public class Transient(
        message: String,
        cause: Throwable? = null,
    ) : ObjectStoreException(message, cause, retryable = true)
}
