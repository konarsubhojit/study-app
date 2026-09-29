package dev.studyflow.app.di

import android.content.Context
import androidx.credentials.CredentialManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.studyflow.app.BuildConfig
import dev.studyflow.feature.auth.CredentialManagerSignInClient
import dev.studyflow.feature.auth.GoogleSignInConfig
import dev.studyflow.feature.auth.PasskeySignInConfig
import dev.studyflow.feature.auth.SignInCredentialProvider
import javax.inject.Singleton

/**
 * Supplies the Google sign-in configuration the Credential Manager request needs (issue #158).
 *
 * The value is a public identifier that ships in the APK by design — it travels in every sign-in
 * request — so it lives in `BuildConfig` rather than a secret store. It must be the Google Cloud
 * OAuth **Web application** client id and must equal the `GOOGLE_SERVER_CLIENT_ID` secret of the
 * `api` Edge Function: the server validates the ID token's audience against its copy, so a
 * mismatch, or the Android client id used by mistake, presents as sign-in that simply never works
 * rather than as a configuration error.
 *
 * Providing it is deliberately fallible. A build that forgot `-Pstudyflow.googleServerClientId`
 * fails here, with the fix in the message, instead of reaching a user as a silent rejection.
 */
@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
    @Provides
    fun credentialProvider(
        @ApplicationContext context: Context,
    ): SignInCredentialProvider = CredentialManagerSignInClient(CredentialManager.create(context))

    @Provides
    @Singleton
    fun googleSignInConfig(): GoogleSignInConfig {
        check(BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank()) {
            "GOOGLE_SERVER_CLIENT_ID is empty; build with -Pstudyflow.googleServerClientId=<web client id> " +
                "(the same value as the api function's GOOGLE_SERVER_CLIENT_ID secret)"
        }
        return GoogleSignInConfig(serverClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID)
    }

    @Provides
    fun passkeySignInConfig(): PasskeySignInConfig = PasskeySignInConfig(enabled = BuildConfig.PASSKEY_SIGN_IN_ENABLED)
}
