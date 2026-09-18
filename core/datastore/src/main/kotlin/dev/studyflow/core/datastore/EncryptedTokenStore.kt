@file:Suppress("DEPRECATION")

package dev.studyflow.core.datastore

import android.annotation.SuppressLint
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.studyflow.core.network.auth.AuthState
import dev.studyflow.core.network.auth.AuthTokens
import dev.studyflow.core.network.auth.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TOKEN_STORE_NAME = "studyflow.auth.tokens"
private const val ACCESS_TOKEN_KEY = "access"
private const val REFRESH_TOKEN_KEY = "refresh"

/**
 * A Keystore-backed token store excluded from Android backup by the application manifest.
 *
 * Local database rows deliberately have no account owner and are never cleared here: upgrading
 * from [AuthState.LocalOnly] therefore uploads the existing device data once through its stable
 * IDs instead of copying it into a second local store.
 */
public class EncryptedTokenStore(
    context: Context,
) : TokenStore {
    private val preferences =
        EncryptedSharedPreferences.create(
            context.applicationContext,
            TOKEN_STORE_NAME,
            MasterKey
                .Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private val mutableAuthState = MutableStateFlow(readTokens().toAuthState())

    override val authState: StateFlow<AuthState> = mutableAuthState.asStateFlow()

    override suspend fun tokens(): AuthTokens? = readTokens()

    override suspend fun update(tokens: AuthTokens) {
        // commit() is required (not apply()) so we synchronously verify the write succeeded.
        @SuppressLint("ApplySharedPref")
        val persisted =
            preferences
                .edit()
                .putString(ACCESS_TOKEN_KEY, tokens.accessToken)
                .putString(REFRESH_TOKEN_KEY, tokens.refreshToken)
                .commit()
        check(persisted) { "Unable to persist authentication tokens" }
        mutableAuthState.value = AuthState.SignedIn
    }

    override suspend fun clear() {
        // commit() is required (not apply()) so we synchronously verify the write succeeded.
        @SuppressLint("ApplySharedPref")
        val cleared = preferences.edit().clear().commit()
        check(cleared) { "Unable to clear authentication tokens" }
        mutableAuthState.value = AuthState.LocalOnly
    }

    private fun readTokens(): AuthTokens? {
        val accessToken = preferences.getString(ACCESS_TOKEN_KEY, null)
        val refreshToken = preferences.getString(REFRESH_TOKEN_KEY, null)
        return if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            null
        } else {
            AuthTokens(accessToken, refreshToken)
        }
    }
}

private fun AuthTokens?.toAuthState(): AuthState = if (this == null) AuthState.LocalOnly else AuthState.SignedIn
