package dev.studyflow.core.storage.presigned

import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.storage.ObjectStoreException
import dev.studyflow.core.storage.PresignedUrl
import dev.studyflow.core.storage.SignedPart
import dev.studyflow.core.storage.StoredObject
import dev.studyflow.core.storage.UploadRequest
import dev.studyflow.core.storage.UploadSession
import dev.studyflow.core.storage.UploadedPart
import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * The production [ObjectStore]: our BFF signs, the device transfers (issue #36).
 *
 * This adapter is provider-agnostic on purpose. It speaks plain HTTP `PUT` to whatever URL
 * [PresignedUrlSource] hands it, which is the one thing Supabase Storage, Cloudflare R2 and any
 * other S3-compatible bucket all do identically — so changing provider changes the BFF, not this
 * class and not a single line above it (ADR 0010).
 *
 * No credential is involved anywhere in this file, and none may ever be: an expired URL is a
 * `403`, which the caller recovers from by asking for a new one.
 */
public class PresignedObjectStore(
    private val client: HttpClient,
    private val urls: PresignedUrlSource,
) : ObjectStore {
    override suspend fun initUpload(request: UploadRequest): UploadSession = urls.createUpload(request)

    // Every transport failure is the same failure to a caller: the part did not arrive and the
    // upload should be retried, whether the engine threw an IOException or something stranger.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun uploadPart(
        session: UploadSession,
        part: SignedPart,
        bytes: ByteArray,
    ): UploadedPart {
        require(bytes.size.toLong() == part.size) {
            "part ${part.number} covers ${part.size} bytes but ${bytes.size} were supplied"
        }

        val response =
            try {
                client.put(part.url.url) { setBody(bytes) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // The URL is a bearer credential, and engine exceptions routinely carry the failed
                // request URL in their message, so the cause is named but never attached: nothing
                // that reaches a log line or a crash report may contain the signature.
                throw ObjectStoreException.Transient(
                    "part ${part.number} of '${session.key}' failed: ${failure::class.simpleName}",
                )
            }

        if (!response.status.isSuccess()) {
            throw response.status.toFailure("part ${part.number} of '${session.key}'")
        }

        return UploadedPart(
            number = part.number,
            etag = response.entityTag(),
            size = part.size,
        )
    }

    override suspend fun completeUpload(
        session: UploadSession,
        parts: List<UploadedPart>,
    ): StoredObject = urls.finishUpload(session, parts)

    override suspend fun getDownloadUrl(
        key: ObjectKey,
        ttl: Duration,
    ): PresignedUrl = urls.downloadUrl(key, ttl)

    override suspend fun delete(key: ObjectKey) {
        urls.delete(key)
    }

    override suspend fun stat(key: ObjectKey): StoredObject? = urls.stat(key)

    /**
     * The provider's identifier for the part, quoted in the response as an entity tag.
     *
     * Empty when the provider does not send one — Supabase's storage API does not for a plain
     * upload, and `completeUpload` there identifies parts by number.
     */
    private fun HttpResponse.entityTag(): String = headers[HttpHeaders.ETag].orEmpty().trim('"', ' ')
}

/**
 * Maps the status of a *part upload* onto the store's own failure vocabulary.
 *
 * A `404` here is a URL the provider no longer recognises rather than a missing object — the object
 * does not exist until `completeUpload` — so it maps to `AccessDenied`; `NotFound` belongs to the
 * key-addressed operations, which the BFF answers.
 *
 * [subject] names the part rather than the URL, which carries the signature.
 */
private fun HttpStatusCode.toFailure(subject: String): ObjectStoreException =
    when (this) {
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.NotFound -> {
            ObjectStoreException.AccessDenied("$subject was refused ($value); a fresh signed URL is needed")
        }

        HttpStatusCode.PayloadTooLarge, HttpStatusCode.InsufficientStorage -> {
            ObjectStoreException.QuotaExceeded("$subject exceeds the storage allowance ($value)")
        }

        HttpStatusCode.RequestTimeout, HttpStatusCode.TooManyRequests -> {
            ObjectStoreException.Transient("$subject was throttled ($value)")
        }

        else -> {
            if (value >= HttpStatusCode.InternalServerError.value) {
                ObjectStoreException.Transient("$subject failed with $value")
            } else {
                ObjectStoreException.AccessDenied("$subject was rejected with $value")
            }
        }
    }
