package dev.studyflow.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.network.auth.TokenStore
import dev.studyflow.feature.auth.AuthSession
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthSessionModule {
    @Provides
    @Singleton
    fun authSession(tokenStore: TokenStore): AuthSession = AuthSession(tokenStore)
}
