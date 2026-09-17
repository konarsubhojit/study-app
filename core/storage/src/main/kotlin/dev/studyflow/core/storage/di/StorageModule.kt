package dev.studyflow.core.storage.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.storage.local.InMemoryObjectStore
import javax.inject.Singleton

/**
 * The one place the storage provider is chosen (issue #36).
 *
 * Everything above [ObjectStore] is written against the interface, so this file is the entire cost
 * of the decision recorded in `docs/adr/0009-storage-provider.md`: making the app talk to Supabase
 * Storage — or to R2, or to a local MinIO — is a different `@Provides` here plus a binding for the
 * [dev.studyflow.core.storage.presigned.PresignedUrlSource] the BFF (#7) will serve, and no change
 * in any feature, repository or worker.
 *
 * Until that BFF exists the app runs on the local adapter, which is exactly the offline-only build
 * the epic calls for rather than a placeholder.
 */
@Module
@InstallIn(SingletonComponent::class)
public object StorageModule {
    @Provides
    @Singleton
    public fun objectStore(): ObjectStore = InMemoryObjectStore()
}
