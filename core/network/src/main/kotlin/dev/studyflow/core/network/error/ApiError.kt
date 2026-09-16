package dev.studyflow.core.network.error

import dev.studyflow.core.network.version.ClientVersion

/**
 * What the UI is allowed to say about a failure (issue #63, domain error model of issue #19).
 *
 * The key — not the text — is what a Compose screen looks up in its string resources, so the
 * copy can be translated and reworded without touching the network layer. [defaultText] exists so
 * that a headless caller (a worker, a log line, a test) still has something readable.
 *
 * There is deliberately no entry that contains an HTTP status code: a user cannot act on "503".
 */
public enum class UserFacingMessage(public val defaultText: String) {
    Offline("You're offline. StudyFlow will sync as soon as you're back."),
    Slow("The connection is too slow right now. Please try again."),
    SignInRequired("Please sign in again to continue."),
    NotAllowed("Your account doesn't have access to that."),
    Missing("That item is no longer available."),
    Conflict("Someone changed this elsewhere. Reopen it to see the latest version."),
    TooManyRequests("You've done that a lot just now. Please wait a moment and try again."),
    ServerProblem("StudyFlow is having trouble. Please try again shortly."),
    UpgradeRequired("Update StudyFlow to keep syncing your study data."),
    Unexpected("Something went wrong. Please try again."),
}

/**
 * Every way a StudyFlow API call can fail, as a closed set (issue #63).
 *
 * Closed because `when` over it is exhaustive: a new failure mode cannot be added without every
 * caller being told to handle it, which is the point of doing this at build time rather than
 * discovering it in a crash report.
 *
 * [code] is the server's machine-readable error code where one was sent. It is for logging,
 * telemetry and branching — never for display; [message] is the one thing a screen may show.
 */
public sealed class ApiError(
    public val message: UserFacingMessage,
    public val code: String? = null,
    public val cause: Throwable? = null,
) {
    /** The request never reached the server: no connectivity, DNS failure, refused connection. */
    public class Offline(cause: Throwable? = null) : ApiError(UserFacingMessage.Offline, cause = cause)

    /** The request reached the server but no answer arrived within the configured timeout. */
    public class Timeout(cause: Throwable? = null) : ApiError(UserFacingMessage.Slow, cause = cause)

    /** Credentials are missing, expired beyond refresh, or rejected. */
    public class Unauthorized(code: String? = null) : ApiError(UserFacingMessage.SignInRequired, code)

    /** Authenticated, but not permitted. */
    public class Forbidden(code: String? = null) : ApiError(UserFacingMessage.NotAllowed, code)

    /** The resource does not exist, or no longer does. */
    public class NotFound(code: String? = null) : ApiError(UserFacingMessage.Missing, code)

    /** The server's copy has moved on since the client's. */
    public class Conflict(code: String? = null) : ApiError(UserFacingMessage.Conflict, code)

    /**
     * Rate limited or shed under load.
     *
     * @property retryAfter seconds the server asked the client to wait, when it said.
     */
    public class RateLimited(
        public val retryAfter: Long? = null,
        code: String? = null,
    ) : ApiError(UserFacingMessage.TooManyRequests, code)

    /** The server failed; the request itself was fine and may be retried later. */
    public class Server(code: String? = null) : ApiError(UserFacingMessage.ServerProblem, code)

    /**
     * This build is older than the oldest client the server supports, or called an endpoint that
     * has completed its deprecation window. The only way out is the store.
     *
     * @property minimumSupported the version the user must upgrade to, when the server said.
     */
    public class UpgradeRequired(
        public val minimumSupported: ClientVersion? = null,
        code: String? = null,
    ) : ApiError(UserFacingMessage.UpgradeRequired, code)

    /**
     * The response did not match the contract: a required field was missing or had the wrong type.
     *
     * An *unknown* field is not this — those are ignored on purpose. This is a genuine contract
     * breach and should page whoever changed the server.
     */
    public class Malformed(cause: Throwable? = null) : ApiError(UserFacingMessage.Unexpected, cause = cause)

    /** Anything not covered above, including 4xx codes the client does not know. */
    public class Unexpected(
        code: String? = null,
        cause: Throwable? = null,
    ) : ApiError(UserFacingMessage.Unexpected, code, cause)

    override fun toString(): String = "${this::class.simpleName}(code=$code, message=${message.name})"
}
