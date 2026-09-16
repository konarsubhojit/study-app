package dev.studyflow.core.network.auth

/**
 * The token pair the client holds on behalf of the signed-in user (issue #63).
 *
 * `toString` is overridden because a token in a log line or a crash report is a credential leak,
 * and data classes print their properties by default.
 */
public data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
) {
    init {
        require(accessToken.isNotBlank()) { "AuthTokens.accessToken must not be blank" }
        require(refreshToken.isNotBlank()) { "AuthTokens.refreshToken must not be blank" }
    }

    override fun toString(): String = "AuthTokens(redacted)"
}

/**
 * Where tokens live between calls.
 *
 * The network layer owns the interface and nothing else: the actual storage is encrypted
 * preferences or the keystore, which is `:core:datastore`'s problem and an Android one.
 */
public interface TokenStore {
    /** The current tokens, or `null` when nobody is signed in. */
    public suspend fun tokens(): AuthTokens?

    /** Replaces the stored tokens after a sign-in or a refresh. */
    public suspend fun update(tokens: AuthTokens)

    /** Drops the tokens after a sign-out, or after a refresh the server rejected. */
    public suspend fun clear()
}

/**
 * Exchanges a refresh token for a fresh pair.
 *
 * Kept separate from [dev.studyflow.core.network.StudyFlowApi] so that the refresh call itself can
 * use a plain, unauthenticated client and can never recurse into its own 401 handling.
 */
public fun interface TokenRefresher {
    /**
     * @return the new tokens, or `null` when the refresh token is no longer valid and the user has
     *   to sign in again.
     */
    public suspend fun refresh(refreshToken: String): AuthTokens?
}

/** An in-memory [TokenStore], for tests and for a build that has no persistence yet. */
public class InMemoryTokenStore(initial: AuthTokens? = null) : TokenStore {
    private var current: AuthTokens? = initial

    override suspend fun tokens(): AuthTokens? = current

    override suspend fun update(tokens: AuthTokens) {
        current = tokens
    }

    override suspend fun clear() {
        current = null
    }
}
