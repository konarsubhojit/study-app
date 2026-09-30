package dev.studyflow.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.common.logging.LogBuffer
import dev.studyflow.core.domain.sync.SyncScheduler
import dev.studyflow.core.domain.sync.SyncStore
import dev.studyflow.core.domain.sync.SyncTrigger
import dev.studyflow.core.network.auth.TokenStore
import dev.studyflow.feature.auth.AuthSession
import dev.studyflow.feature.auth.RemoteCacheCleaner
import dev.studyflow.feature.auth.SignInListener
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthSessionModule {
    @Provides
    @Singleton
    fun authSession(
        tokenStore: TokenStore,
        syncStore: SyncStore,
        syncScheduler: SyncScheduler,
        logBuffer: LogBuffer,
    ): AuthSession =
        AuthSession(
            tokenStore = tokenStore,
            // The sync cursors and "already sent" state describe the account being left; the next
            // account must read its history from the start and receive this device's data.
            // The buffered log describes the account being left — which of its syncs failed, how
            // many of its rows were dropped — so it is account-scoped state too, and the next
            // account must not be able to export it.
            remoteCacheCleaner =
                RemoteCacheCleaner {
                    syncStore.resetForAccountChange()
                    logBuffer.clear()
                },
            signInListener = SignInListener { syncScheduler.requestSync(SyncTrigger.MANUAL) },
        )
}
