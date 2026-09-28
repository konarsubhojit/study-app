package dev.studyflow.feature.auth

import dev.studyflow.core.network.auth.AuthState
import dev.studyflow.core.network.auth.AuthTokens
import dev.studyflow.core.network.auth.TokenStore
import kotlinx.coroutines.flow.StateFlow

/** Owns account transitions; feature modules only consume [state]. */
public class AuthSession(
    private val tokenStore: TokenStore,
    private val remoteCacheCleaner: RemoteCacheCleaner = RemoteCacheCleaner { },
    private val signInListener: SignInListener = SignInListener { },
) {
    public val state: StateFlow<AuthState> = tokenStore.authState

    /**
     * Stores the new account's tokens, then tells [signInListener] — which in the app requests a
     * sync, so a second device fills with the account's data without waiting for the next
     * periodic run.
     */
    public suspend fun completeSignIn(tokens: AuthTokens) {
        tokenStore.update(tokens)
        signInListener.onSignedIn()
    }

    /**
     * Keeps local content by design. Remote cache cleanup runs after credentials are removed, so it
     * cannot repopulate data while the next account is being selected.
     */
    public suspend fun signOut() {
        tokenStore.clear()
        remoteCacheCleaner.clear()
    }
}

/** Clears account-scoped replicas without touching the device's local-first study data. */
public fun interface RemoteCacheCleaner {
    public suspend fun clear()
}

/** Runs once credentials for a newly signed-in account are stored. */
public fun interface SignInListener {
    public suspend fun onSignedIn()
}
