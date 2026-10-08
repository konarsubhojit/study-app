package dev.studyflow.core.storage.presigned

import dev.studyflow.core.common.network.isHostResolutionFailure
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.SystemWallClock
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The production [ObjectStore]: our BFF signs, the device transfers (issue #36).
 *
 * This adapter is provider-agnostic on purpose. It speaks plain HTTP `PUT` to whatever URL
 * [PresignedUrlSource] hands it, which is the one thing Supabase Storage, Cloudflare R2 and any
 * other S3-compatible bucket all do identically — so changing provider changes the BFF, not this
 * class and not a single line above it (ADR 0010).
 *
 * No credential is involved anywhere in this file, and none may ever be. [client] must be a plain
 * client with no auth plugin: the signature in the URL is the only authority a part `PUT` carries,
 * and attaching the user's token to a third-party host would leak it.
 *
 * ### Expired signatures
 *
 * A part URL that has expired — known from its `expiresAt`, or learned from a `401`/`403` — is
 * renewed here rather than surfaced: the source is asked to open the same upload again, which for
 * an identical pending request returns the *same* `uploadId` with freshly signed URLs, and the
 * part is sent once more to its new URL. An unavailable renewal is reported as
 * [ObjectStoreException.AccessDenied]. A replaced upload is retryable so the upload engine can
 * reopen it and discard receipts belonging to the old session.
 *
 * ### Download URL reuse
 *
 * [getDownloadUrl] keeps the last URL signed for each key in memory and hands it out again
 * instead of asking the BFF to sign another, as long as it is still valid for at least
 * [DOWNLOAD_URL_REUSE_MARGIN] (so a transfer that starts now cannot meet an expired signature)
 * and it expires no later than the caller's requested TTL (so a caller asking for a short-lived
 * URL never receives a longer-lived one). [delete] drops the key's entry. The cache only ever
 * holds what callers could already receive, is never persisted, and — like everything else
 * here — never reaches a log line or an exception message.
 *
 * A download refused with `401`/`403` cannot evict its URL through [ObjectStore], whose
 * interface stays provider-neutral. Expiry cannot cause that refusal, because of the margin
 * above. A signature the provider *revokes* early (a key rotation) can: the URL may then be
 * handed out again until it falls inside the margin, at most [ObjectStore.MAX_PRESIGNED_URL_TTL]
 * after it was signed, after which a freshly signed URL is fetched.
 */
public class PresignedObjectStore(
    private val client: HttpClient,
    private val urls: PresignedUrlSource,
    private val clock: Clock = SystemWallClock,
) : ObjectStore {
    /** The request behind each open session, so an expired part URL can be re-signed. */
    private val openRequests = ConcurrentHashMap<String, UploadRequest>()

    /** The last download URL signed per key; reused while [isReusable] says it still fits. */
    private val downloadUrls = ConcurrentHashMap<ObjectKey, PresignedUrl>()

    override suspend fun initUpload(request: UploadRequest): UploadSession =
        urls.createUpload(request).also { session -> openRequests[session.uploadId] = request }

    override suspend fun uploadPart(
        session: UploadSession,
        part: SignedPart,
        bytes: ByteArray,
    ): UploadedPart {
        require(bytes.size.toLong() == part.size) {
            "part ${part.number} covers ${part.size} bytes but ${bytes.size} were supplied"
        }

        val expiredBeforeSending = clock.now() >= part.url.expiresAt
        val target = if (expiredBeforeSending) renew(session, part) else part
        var response = put(session, target, bytes)
        // Renew at most once per call: a second refusal is not an expiry and is reported as such.
        if (!expiredBeforeSending && response.status.isRefusedSignature()) {
            response = put(session, renew(session, part), bytes)
        }
        if (!response.status.isSuccess()) {
            throw response.status.toFailure("part ${part.number} of '${session.key}'")
        }
        val etag = response.entityTag()
        if (etag.isBlank()) {
            throw ObjectStoreException.Integrity(
                "storage provider did not return an entity tag for part ${part.number}",
            )
        }
        return UploadedPart(
            number = part.number,
            etag = etag,
            size = part.size,
        )
    }

    // A dropped connection can recover, but an unresolvable storage host needs configuration fixed.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun put(
        session: UploadSession,
        part: SignedPart,
        bytes: ByteArray,
    ): HttpResponse =
        try {
            client.put(part.url.url) {
                part.requiredHeaders.forEach { (name, value) -> headers.append(name, value) }
                setBody(bytes)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // The URL is a bearer credential, and engine exceptions routinely carry the failed
            // request URL in their message, so the cause is named but never attached: nothing
            // that reaches a log line or a crash report may contain the signature.
            throw if (failure.isHostResolutionFailure()) {
                ObjectStoreException.BackendUnreachable(
                    "part ${part.number} of '${session.key}' failed: ${failure::class.simpleName}",
                )
            } else {
                ObjectStoreException.Transient(
                    "part ${part.number} of '${session.key}' failed: ${failure::class.simpleName}",
                )
            }
        }

    /** Re-signs [part] in the same upload; a replacement session must restart at the engine. */
    private suspend fun renew(
        session: UploadSession,
        part: SignedPart,
    ): SignedPart {
        val subject = "part ${part.number} of '${session.key}'"
        val request = openRequests[session.uploadId]
        val fresh = request?.let { urls.createUpload(it) }
        if (fresh != null && fresh.uploadId != session.uploadId) {
            openRequests.remove(session.uploadId)
            throw ObjectStoreException.Transient("$subject belongs to an upload that has been replaced")
        }
        val problem =
            when {
                fresh == null -> "has an expired URL; a fresh signed URL is needed"
                else -> "is no longer part of the upload"
            }
        val renewed =
            fresh
                ?.takeIf { it.uploadId == session.uploadId }
                ?.parts
                ?.firstOrNull { it.number == part.number && it.part == part.part }
        return renewed ?: throw ObjectStoreException.AccessDenied("$subject $problem")
    }

    override suspend fun completeUpload(
        session: UploadSession,
        parts: List<UploadedPart>,
    ): StoredObject = urls.finishUpload(session, parts).also { openRequests.remove(session.uploadId) }

    override suspend fun getDownloadUrl(
        key: ObjectKey,
        ttl: Duration,
    ): PresignedUrl {
        require(ttl > Duration.ZERO) { "download URL TTL must be positive" }
        val effectiveTtl = minOf(ttl, ObjectStore.MAX_PRESIGNED_URL_TTL)
        downloadUrls[key]?.takeIf { it.isReusable(effectiveTtl) }?.let { return it }
        return urls.downloadUrl(key, effectiveTtl).also { fresh -> downloadUrls[key] = fresh }
    }

    private fun PresignedUrl.isReusable(ttl: Duration): Boolean {
        val now = clock.now()
        return expiresAt >= now + DOWNLOAD_URL_REUSE_MARGIN && expiresAt <= now + ttl
    }

    override suspend fun delete(key: ObjectKey) {
        // Evicted even when the delete fails, and after it, so a URL signed meanwhile is not kept.
        try {
            urls.delete(key)
        } finally {
            downloadUrls.remove(key)
        }
    }

    override suspend fun stat(key: ObjectKey): StoredObject? = urls.stat(key)

    /** The provider's identifier for the part, preserved exactly as returned in the response. */
    private fun HttpResponse.entityTag(): String = headers[HttpHeaders.ETag].orEmpty()

    public companion object {
        /** How long a cached download URL must stay valid to be handed out again. */
        public val DOWNLOAD_URL_REUSE_MARGIN: Duration = 2.minutes
    }
}

private fun HttpStatusCode.isRefusedSignature(): Boolean =
    this == HttpStatusCode.Forbidden || this == HttpStatusCode.Unauthorized

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
