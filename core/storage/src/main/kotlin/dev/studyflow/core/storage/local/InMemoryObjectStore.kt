package dev.studyflow.core.storage.local

import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.SystemWallClock
import dev.studyflow.core.domain.materials.MultipartLimits
import dev.studyflow.core.domain.materials.UploadPlanner
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.storage.ObjectStoreException
import dev.studyflow.core.storage.PresignedUrl
import dev.studyflow.core.storage.SignedPart
import dev.studyflow.core.storage.StoredObject
import dev.studyflow.core.storage.UploadRequest
import dev.studyflow.core.storage.UploadSession
import dev.studyflow.core.storage.UploadedPart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * An [ObjectStore] that keeps everything in this process (issue #36).
 *
 * It exists twice over: it is the store the offline-only build variant runs on, and it is the store
 * every test of the materials feature runs on — so the catalogue, the upload worker and the
 * previewers can be built and verified before the BFF (#7) serves its first request, with no
 * network, no Docker and no account.
 *
 * It is a real implementation of the contract rather than a stub: part boundaries come from the
 * same [UploadPlanner] the cloud path uses, URLs expire, an unknown upload is refused and the
 * assembled object is verified against the digest the caller declared — because a fake that only
 * ever succeeds tests nothing.
 *
 * Contents live for the lifetime of the instance, so the offline-only variant relies on the
 * catalogue's on-disk staging copy for durability (ADR 0005).
 */
public class InMemoryObjectStore(
    private val clock: Clock = SystemWallClock,
    private val limits: MultipartLimits = MultipartLimits.S3_COMPATIBLE,
    private val urlTtl: Duration = DEFAULT_URL_TTL,
) : ObjectStore {
    private val mutex = Mutex()
    private val objects = mutableMapOf<ObjectKey, StoredBlob>()
    private val uploads = mutableMapOf<String, PendingUpload>()
    private var nextUploadId = 0L

    override suspend fun initUpload(request: UploadRequest): UploadSession =
        mutex.withLock {
            val uploadId = "upload-${++nextUploadId}"
            val expiresAt = clock.now() + urlTtl
            val session =
                UploadSession.of(
                    key = request.key,
                    uploadId = uploadId,
                    plan = UploadPlanner.plan(request.sizeBytes, request.contentHash, limits),
                    expiresAt = expiresAt,
                ) { part ->
                    PresignedUrl(
                        url = "${PresignedUrl.LOCAL_SCHEME}uploads/$uploadId/parts/${part.number}",
                        expiresAt = expiresAt,
                    )
                }
            uploads[uploadId] = PendingUpload(request)
            session
        }

    override suspend fun uploadPart(
        session: UploadSession,
        part: SignedPart,
        bytes: ByteArray,
    ): UploadedPart {
        require(bytes.size.toLong() == part.size) {
            "part ${part.number} covers ${part.size} bytes but ${bytes.size} were supplied"
        }

        return mutex.withLock {
            val upload = activeUpload(session)
            upload.parts[part.number] = bytes.copyOf()
            UploadedPart(number = part.number, etag = "part-${part.number}", size = part.size)
        }
    }

    override suspend fun completeUpload(
        session: UploadSession,
        parts: List<UploadedPart>,
    ): StoredObject =
        mutex.withLock {
            val upload = activeUpload(session)
            val acknowledged = parts.map { it.number }.toSet()
            val missing = session.parts.map { it.number }.filterNot { it in acknowledged }
            if (missing.isNotEmpty()) {
                throw ObjectStoreException.Integrity("parts $missing of '${session.key}' were not acknowledged")
            }

            val assembled =
                session.parts.fold(ByteArray(0)) { bytes, part ->
                    val uploaded =
                        upload.parts[part.number]
                            ?: throw ObjectStoreException.Integrity(
                                "part ${part.number} of '${session.key}' was never uploaded",
                            )
                    bytes + uploaded
                }

            val digest = ContentHash(sha256Hex(assembled))
            if (digest != upload.request.contentHash) {
                throw ObjectStoreException.Integrity(
                    "'${session.key}' hashes to $digest but ${upload.request.contentHash} was expected",
                )
            }

            val stored =
                StoredObject(
                    key = session.key,
                    sizeBytes = assembled.size.toLong(),
                    contentType = upload.request.contentType,
                    contentHash = digest,
                    updatedAt = clock.now(),
                )
            objects[session.key] = StoredBlob(stored, assembled)
            uploads.remove(session.uploadId)
            stored
        }

    override suspend fun getDownloadUrl(
        key: ObjectKey,
        ttl: Duration,
    ): PresignedUrl =
        mutex.withLock {
            if (key !in objects) throw ObjectStoreException.NotFound(key)
            PresignedUrl(
                url = "${PresignedUrl.LOCAL_SCHEME}objects/${key.value}",
                // A store may shorten a requested lifetime but never extend it.
                expiresAt = clock.now() + minOf(ttl, urlTtl),
            )
        }

    override suspend fun delete(key: ObjectKey) {
        mutex.withLock { objects.remove(key) }
    }

    override suspend fun stat(key: ObjectKey): StoredObject? = mutex.withLock { objects[key]?.stored }

    /** The stored bytes, so a test can assert what the upload actually produced. */
    public suspend fun bytesOf(key: ObjectKey): ByteArray? = mutex.withLock { objects[key]?.bytes?.copyOf() }

    private fun activeUpload(session: UploadSession): PendingUpload {
        val upload =
            uploads[session.uploadId]
                ?: throw ObjectStoreException.AccessDenied(
                    "upload '${session.uploadId}' is unknown or has already been completed",
                )
        if (clock.now() >= session.expiresAt) {
            throw ObjectStoreException.AccessDenied("upload '${session.uploadId}' has expired")
        }
        return upload
    }

    private class PendingUpload(
        val request: UploadRequest,
    ) {
        val parts: MutableMap<Int, ByteArray> = mutableMapOf()
    }

    private class StoredBlob(
        val stored: StoredObject,
        val bytes: ByteArray,
    )

    public companion object {
        /** Matches [ObjectStore.DEFAULT_DOWNLOAD_TTL]: short-lived here too, so tests see expiry. */
        public val DEFAULT_URL_TTL: Duration = 15.minutes

        /** Lower-case hex SHA-256, the digest format [ContentHash] is defined in. */
        public fun sha256Hex(bytes: ByteArray): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
