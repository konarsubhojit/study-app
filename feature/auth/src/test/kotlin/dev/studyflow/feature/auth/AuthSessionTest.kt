package dev.studyflow.feature.auth

import dev.studyflow.core.network.auth.AuthState
import dev.studyflow.core.network.auth.AuthTokens
import dev.studyflow.core.network.auth.InMemoryTokenStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AuthSessionTest {
    @Test
    fun `local-only mode becomes signed in without changing local repositories`() =
        runTest {
            val session = AuthSession(InMemoryTokenStore())

            assertEquals(AuthState.LocalOnly, session.state.value)

            session.completeSignIn(AuthTokens("access", "refresh"))

            assertEquals(AuthState.SignedIn, session.state.value)
        }

    @Test
    fun `sign out clears credentials and account cache only`() =
        runTest {
            val tokens = InMemoryTokenStore(AuthTokens("access", "refresh"))
            var cacheCleared = false
            val session = AuthSession(tokens, RemoteCacheCleaner { cacheCleared = true })

            session.signOut()

            assertNull(tokens.tokens())
            assertEquals(AuthState.LocalOnly, session.state.value)
            assertEquals(true, cacheCleared)
        }
}
