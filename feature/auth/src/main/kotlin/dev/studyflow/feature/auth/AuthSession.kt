package dev.studyflow.feature.auth

import dev.studyflow.core.network.auth.AuthState
import dev.studyflow.core.network.auth.AuthTokens
import dev.studyflow.core.network.auth.TokenStore
import kotlinx.coroutines.flow.StateFlow

/** Owns account transitions; feature modules only consume [state]. */
public class AuthSession(
    private val tokenStore: TokenStore,
    private val remoteCacheCleaner: RemoteCacheCleaner = RemoteCacheCleaner { },
) {
    public val state: StateFlow<AuthState> = tokenStore.authState

    public suspend fun completeSignIn(tokens: AuthTokens) {
        tokenStore.update(tokens)
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
