package dev.studyflow.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.common.coroutines.ApplicationScope
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.coroutines.StandardDispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConcurrencyModule {
    @Provides
    @Singleton
    fun dispatcherProvider(): DispatcherProvider = StandardDispatcherProvider

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(dispatcherProvider: DispatcherProvider): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcherProvider.default)
}
