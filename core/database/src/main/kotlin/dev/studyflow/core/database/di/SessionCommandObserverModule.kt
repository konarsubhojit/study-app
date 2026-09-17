package dev.studyflow.core.database.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import dev.studyflow.core.domain.session.SessionCommandObserver

/** Declares the optional observer set used for post-commit timer side effects. */
@Module
@InstallIn(SingletonComponent::class)
public interface SessionCommandObserverModule {
    @Multibinds
    public fun sessionCommandObservers(): Set<@JvmSuppressWildcards SessionCommandObserver>
}
