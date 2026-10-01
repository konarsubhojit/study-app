package dev.studyflow.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.studyflow.app.BuildConfig
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.storage.presigned.PresignedObjectStore
import dev.studyflow.core.storage.presigned.PresignedUrlSource
import dev.studyflow.core.storage.presigned.StorageFunctionUrlSource
import dev.studyflow.core.storage.presigned.signedUrlHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import javax.inject.Singleton

/**
 * Material bytes go to the bucket behind the `storage` Edge Function (ADR 0010).
 *
 * The function holds the storage credential and signs short-lived part and download URLs for the
 * user its JWT names; the device only ever holds those URLs. Two clients are involved on purpose:
 * the authenticated API client (bearer token, JSON) talks to the function, and a bare client with
 * no auth sends bytes to the signed URLs, so the user's token never reaches the provider's host.
 *
 * The mock flavour binds the in-memory store instead, so it stays offline end to end.
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun presignedUrlSource(client: HttpClient): PresignedUrlSource =
        StorageFunctionUrlSource(client = client, baseUrl = BuildConfig.STORAGE_BASE_URL)

    @Provides
    @Singleton
    fun objectStore(
        engine: HttpClientEngine,
        urls: PresignedUrlSource,
    ): ObjectStore = PresignedObjectStore(client = signedUrlHttpClient(engine), urls = urls)
}
