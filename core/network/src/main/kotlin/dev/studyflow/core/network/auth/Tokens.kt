package dev.studyflow.core.network.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

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
    /**
     * The sole app-wide authentication state. Features remain local-first and do not inspect
     * credentials themselves.
     */
    public val authState: StateFlow<AuthState>

    /** The current tokens, or `null` when nobody is signed in. */
    public suspend fun tokens(): AuthTokens?

    /** Replaces the stored tokens after a sign-in or a refresh. */
    public suspend fun update(tokens: AuthTokens)

    /** Drops the tokens after a sign-out, or after a refresh the server rejected. */
    public suspend fun clear()
}

/** A student can use every local feature without creating an account. */
public sealed interface AuthState {
    /** Local content remains on this device and is not synchronized. */
    public data object LocalOnly : AuthState

    /** Tokens exist and remote synchronization may run for the current account. */
    public data object SignedIn : AuthState
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

/**
 * An in-memory [TokenStore], for tests and for a build that has no persistence yet.
 *
 * The reference is atomic because the store is a singleton, read and written from whichever thread
 * a request or a token refresh happens to run on.
 */
public class InMemoryTokenStore(
    initial: AuthTokens? = null,
) : TokenStore {
    private val current = AtomicReference(initial)
    private val mutableAuthState = MutableStateFlow(initial.toAuthState())

    override val authState: StateFlow<AuthState> = mutableAuthState.asStateFlow()

    override suspend fun tokens(): AuthTokens? = current.get()

    override suspend fun update(tokens: AuthTokens) {
        current.set(tokens)
        mutableAuthState.value = AuthState.SignedIn
    }

    override suspend fun clear() {
        current.set(null)
        mutableAuthState.value = AuthState.LocalOnly
    }
}

private fun AuthTokens?.toAuthState(): AuthState = if (this == null) AuthState.LocalOnly else AuthState.SignedIn
