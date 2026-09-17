package dev.studyflow.core.database.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import dev.studyflow.core.domain.session.SessionCommandObserver

@Module
@InstallIn(SingletonComponent::class)
public abstract class SessionCommandObserverModule {
    @Multibinds
    public abstract fun sessionCommandObservers(): Set<@JvmSuppressWildcards SessionCommandObserver>
}
