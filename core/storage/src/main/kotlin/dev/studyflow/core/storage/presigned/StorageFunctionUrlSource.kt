package dev.studyflow.core.storage.presigned

import dev.studyflow.core.common.network.isHostResolutionFailure
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.SystemWallClock
import dev.studyflow.core.domain.materials.CloudStorageLimits
import dev.studyflow.core.domain.materials.UploadPart
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStoreException
import dev.studyflow.core.storage.PresignedUrl
import dev.studyflow.core.storage.SignedPart
import dev.studyflow.core.storage.StoredObject
import dev.studyflow.core.storage.UploadRequest
import dev.studyflow.core.storage.UploadSession
import dev.studyflow.core.storage.UploadedPart
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * [PresignedUrlSource] backed by the `storage` Supabase Edge Function
 * (`infra/supabase/functions/storage/index.ts`).
 *
 * ### Keys
 *
 * The function derives every object key itself, as `<owner>/<sha256>`, from the user the request's
 * JWT authenticates — the `storage_uploads_key_scoped_to_owner` constraint enforces it. The app's
 * keys are `materials/<sha256>` ([ObjectKey.ofMaterial]). The translation is split across the
 * wire on purpose: this class strips the `materials/` namespace and sends only the content hash,
 * and the server prefixes the owner. The client never knows, stores or sends an owner id, and a
 * catalogue row's `remoteKey` means the same object on every device the same user signs in on.
 * Keys outside the `materials/` namespace are not served by the function at all, so they read as
 * absent.
 *
 * ### Credentials
 *
 * [client] is the app's authenticated API client: it carries the user's JWT and nothing else. The
 * S3 credentials stay inside the Edge Function; what comes back is URLs that expire.
 *
 * @param baseUrl the function's URL, e.g. `https://<project-ref>.supabase.co/functions/v1/storage`;
 *   each operation is a `POST` to `<baseUrl>/<operation>`.
 */
public class StorageFunctionUrlSource(
    private val client: HttpClient,
    baseUrl: String,
    private val clock: Clock = SystemWallClock,
) : PresignedUrlSource {
    private val baseUrl: String = baseUrl.trimEnd('/')

    init {
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) {
            "the storage function URL must be an absolute http(s) URL"
        }
    }

    override suspend fun createUpload(request: UploadRequest): UploadSession {
        val hash =
            request.key.materialContentHash
                ?: throw ObjectStoreException.AccessDenied("'${request.key}' is not a key the storage service stores")
        require(hash == request.contentHash) { "'${request.key}' does not address ${request.contentHash}" }
        val expectedParts = expectedPartCount(request.sizeBytes)
        require(request.partChecksums.size == expectedParts) {
            "'${request.key}' needs $expectedParts part checksums but ${request.partChecksums.size} were computed"
        }

        val response =
            call(
                operation = OP_INIT,
                key = request.key,
                body =
                    buildJsonObject {
                        put("contentHash", hash.hex)
                        put("contentType", request.contentType)
                        put("sizeBytes", request.sizeBytes)
                        put(
                            "partChecksums",
                            buildJsonArray { request.partChecksums.forEach { add(JsonPrimitive(it)) } },
                        )
                    },
            )
        return parse(OP_INIT) {
            UploadSession(
                key = request.key,
                uploadId = response.string("uploadId"),
                parts = response.getValue("parts").jsonArray.map { it.jsonObject.toSignedPart() },
                expiresAt = response.instant("expiresAt"),
            )
        }
    }

    override suspend fun finishUpload(
        session: UploadSession,
        parts: List<UploadedPart>,
    ): StoredObject {
        val response =
            call(
                operation = OP_COMPLETE,
                key = session.key,
                body =
                    buildJsonObject {
                        put("uploadId", session.uploadId)
                        put(
                            "parts",
                            buildJsonArray {
                                parts.sortedBy { it.number }.forEach { part ->
                                    add(
                                        buildJsonObject {
                                            put("number", part.number)
                                            put("etag", part.etag)
                                        },
                                    )
                                }
                            },
                        )
                    },
            )
        return parse(OP_COMPLETE) { response.toStoredObject(session.key) }
    }

    override suspend fun downloadUrl(
        key: ObjectKey,
        ttl: Duration,
    ): PresignedUrl {
        val hash = key.materialContentHash ?: throw ObjectStoreException.NotFound(key)
        val response = call(OP_DOWNLOAD, key, hashBody(hash))
        return parse(OP_DOWNLOAD) {
            // The function signs for a fixed lifetime; a caller asking for less is told the
            // shorter of the two, since a store may shorten a requested lifetime but never extend it.
            PresignedUrl(
                url = response.string("url"),
                expiresAt = minOf(response.instant("expiresAt"), clock.now() + ttl),
            )
        }
    }

    override suspend fun delete(key: ObjectKey) {
        val hash = key.materialContentHash ?: return
        call(OP_DELETE, key, hashBody(hash))
    }

    override suspend fun stat(key: ObjectKey): StoredObject? {
        val hash = key.materialContentHash ?: return null
        val response =
            try {
                call(OP_STAT, key, hashBody(hash))
            } catch (_: ObjectStoreException.NotFound) {
                return null
            }
        return parse(OP_STAT) { response.toStoredObject(key) }
    }

    private suspend fun call(
        operation: String,
        key: ObjectKey,
        body: JsonObject,
    ): JsonObject {
        val (status, text) = post(operation, key, body)
        val json = text.takeIf(String::isNotBlank)?.let(::parseObjectOrNull)
        if (status.isSuccess()) return json ?: JsonObject(emptyMap())
        throw failureFor(operation, key, status, json?.get("code")?.jsonPrimitive?.contentOrNull)
    }

    // DNS configuration failures are permanent; dropped connections and timeouts may recover.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun post(
        operation: String,
        key: ObjectKey,
        body: JsonObject,
    ): Pair<HttpStatusCode, String> =
        try {
            val response =
                client.post("$baseUrl/$operation") {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            response.status to response.bodyAsText()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // Named, not attached: an engine exception can quote the request, and its headers
            // carry the user's token.
            throw if (failure.isHostResolutionFailure()) {
                ObjectStoreException.BackendUnreachable("$operation for '$key' failed: ${failure::class.simpleName}")
            } else {
                ObjectStoreException.Transient("$operation for '$key' failed: ${failure::class.simpleName}")
            }
        }

    private fun hashBody(hash: ContentHash): JsonObject = buildJsonObject { put("contentHash", hash.hex) }

    private fun JsonObject.toSignedPart(): SignedPart =
        SignedPart(
            part = UploadPart(number = int("number"), offset = long("offset"), size = long("size")),
            url = PresignedUrl(url = string("url"), expiresAt = instant("expiresAt")),
            requiredHeaders =
                (get("requiredHeaders") as? JsonObject)
                    ?.mapValues { (_, value) -> value.jsonPrimitive.content }
                    .orEmpty(),
        )

    private fun JsonObject.toStoredObject(key: ObjectKey): StoredObject =
        StoredObject(
            key = key,
            sizeBytes = long("sizeBytes"),
            contentType = string("contentType"),
            contentHash = ContentHash(string("contentHash")),
            // The function reports when the object became ready only from `stat`; for a just-completed
            // upload "now" is the honest answer.
            updatedAt = (get("updatedAt") as? JsonPrimitive)?.contentOrNull?.let(Instant::parse) ?: clock.now(),
        )

    /** A response that does not have the documented shape is a server fault, so it is worth retrying. */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> parse(
        operation: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (failure: IllegalArgumentException) {
            throw ObjectStoreException.Transient("$operation returned an unexpected response", failure)
        } catch (failure: NoSuchElementException) {
            throw ObjectStoreException.Transient("$operation returned an unexpected response", failure)
        }

    public companion object {
        internal const val OP_INIT: String = "initUpload"
        internal const val OP_COMPLETE: String = "completeUpload"
        internal const val OP_DOWNLOAD: String = "getDownloadUrl"
        internal const val OP_DELETE: String = "delete"
        internal const val OP_STAT: String = "stat"

        /** How many parts — and so how many checksums — the function expects for [sizeBytes]. */
        public fun expectedPartCount(sizeBytes: Long): Int =
            ((sizeBytes + CloudStorageLimits.PART_SIZE_BYTES - 1) / CloudStorageLimits.PART_SIZE_BYTES).toInt()

        /**
         * The function's documented failures, translated into the store's vocabulary.
         *
         * `retryable` is the decision that matters: the upload worker reschedules a [Transient]
         * failure and parks everything else as a failure the user has to act on.
         */
        @Suppress("CyclomaticComplexMethod")
        internal fun failureFor(
            operation: String,
            key: ObjectKey,
            status: HttpStatusCode,
            code: String?,
        ): ObjectStoreException {
            val subject = "$operation for '$key' (${status.value} ${code ?: "no code"})"
            return when {
                code == "object_not_found" -> {
                    ObjectStoreException.NotFound(key)
                }

                // Signed out or the session lapsed: the bearer plugin has already tried a refresh,
                // so this resolves once the user signs in again rather than never.
                status == HttpStatusCode.Unauthorized -> {
                    ObjectStoreException.Transient("$subject: not signed in")
                }

                // `upload_initializing` is explicitly "retry shortly"; `object_already_exists` and
                // `upload_not_completable` mean another attempt is finishing the same object, which
                // the next run's `stat` will find.
                status == HttpStatusCode.Conflict -> {
                    ObjectStoreException.Transient(subject)
                }

                // The reservation lapsed (24 h) or was reaped; a fresh `initUpload` opens a new one.
                code == "upload_not_found" -> {
                    ObjectStoreException.Transient(subject)
                }

                // An older deployment without this operation: redeploying fixes it, not the user.
                code == "endpoint_not_found" -> {
                    ObjectStoreException.Transient(
                        "$subject: storage service is out of date",
                    )
                }

                code == "quota_exceeded" -> {
                    ObjectStoreException.QuotaExceeded(subject)
                }

                code == "integrity_mismatch" || code == "scan_rejected" -> {
                    ObjectStoreException.Integrity(subject)
                }

                status == HttpStatusCode.TooManyRequests -> {
                    ObjectStoreException.Transient(subject)
                }

                status.value >= HttpStatusCode.InternalServerError.value -> {
                    ObjectStoreException.Transient(subject)
                }

                // `413 file_too_large`, `415 unsupported_media_type` and every `400`: the request
                // itself is unacceptable and resending it unchanged cannot help.
                else -> {
                    ObjectStoreException.AccessDenied(subject)
                }
            }
        }

        private fun parseObjectOrNull(text: String): JsonObject? =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                null
            }

        private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

        private fun JsonObject.long(name: String): Long =
            requireNotNull(getValue(name).jsonPrimitive.longOrNull) { "'$name' is not a whole number" }

        private fun JsonObject.int(name: String): Int =
            long(name).also { require(it in 1..Int.MAX_VALUE) { "'$name' is out of range" } }.toInt()

        private fun JsonObject.instant(name: String): Instant = Instant.parse(string(name))
    }
}
