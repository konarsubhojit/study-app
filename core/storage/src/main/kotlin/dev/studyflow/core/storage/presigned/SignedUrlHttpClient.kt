package dev.studyflow.core.storage.presigned

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The client [PresignedObjectStore] sends part bytes with.
 *
 * Deliberately bare: no auth plugin, because the signature in the URL is the only authority a
 * part `PUT` carries and the user's token must never travel to the storage provider's host; no
 * default content type, because the signed request does not cover one; no automatic retry,
 * because the upload engine owns retries and resumes from the last acknowledged part. Timeouts
 * are sized for a full part on a slow link rather than for a JSON call.
 *
 * Share the [engine] with the API client — one connection pool is enough.
 */
public fun signedUrlHttpClient(
    engine: HttpClientEngine,
    connectTimeout: Duration = 15.seconds,
    socketTimeout: Duration = 1.minutes,
    requestTimeout: Duration = 10.minutes,
): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeout.inWholeMilliseconds
            socketTimeoutMillis = socketTimeout.inWholeMilliseconds
            requestTimeoutMillis = requestTimeout.inWholeMilliseconds
        }
    }
