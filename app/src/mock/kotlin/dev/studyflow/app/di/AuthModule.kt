package dev.studyflow.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.feature.auth.GoogleSignInConfig
import dev.studyflow.feature.auth.SignInCredential
import dev.studyflow.feature.auth.SignInCredentialProvider

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
    @Provides
    fun googleSignInConfig(): GoogleSignInConfig = GoogleSignInConfig("mock-client-id")

    @Provides
    fun credentialProvider(): SignInCredentialProvider =
        SignInCredentialProvider { _, _, _ -> SignInCredential.GoogleIdToken("mock-id-token") }
}
