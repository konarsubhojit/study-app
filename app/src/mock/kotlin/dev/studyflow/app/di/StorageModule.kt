package dev.studyflow.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.storage.local.InMemoryObjectStore
import javax.inject.Singleton

/**
 * The mock flavour performs no network I/O, so material bytes stay in this process.
 *
 * `InMemoryObjectStore` honours the whole store contract — part boundaries, URL expiry, digest
 * verification — which keeps the upload pipeline exercisable offline. It is never the production
 * binding: bytes held here vanish with the process.
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun objectStore(): ObjectStore = InMemoryObjectStore()
}
