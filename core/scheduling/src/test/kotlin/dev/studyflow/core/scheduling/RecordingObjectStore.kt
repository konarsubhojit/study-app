package dev.studyflow.core.scheduling

import dev.studyflow.core.domain.materials.UploadPlanner
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.SyncState
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A resumable-multipart fake, tracking real object stores' one deviation [InMemoryObjectStore]
 * cannot make for a test: parts are kept by [ObjectKey], not by upload id, so a *second* call to
 * [initUpload] for the same key can still be completed with bytes a prior call already uploaded —
 * exactly what a real provider does for a resumed session, and what [MaterialUploadEngineTest]
 * needs to prove a resumed upload never resends an already-acknowledged part.
 */
internal class RecordingObjectStore : ObjectStore {
    private val mutex = Mutex()
    private val partsByKey = mutableMapOf<ObjectKey, MutableMap<Int, ByteArray>>()

    /** Every part number [uploadPart] was actually called with, in call order. */
    val uploadedPartNumbers = mutableListOf<Int>()

    var failNextUploadPart: ObjectStoreException? = null
    var failCompleteUpload: ObjectStoreException? = null

    override suspend fun initUpload(request: UploadRequest): UploadSession {
        val plan = UploadPlanner.plan(request.sizeBytes, request.contentHash)
        val expiresAt = Instant.parse(EXPIRES_AT)
        return UploadSession.of(request.key, "upload-${request.key}", plan, expiresAt) { part ->
            PresignedUrl("${PresignedUrl.LOCAL_SCHEME}${request.key}/parts/${part.number}", expiresAt)
        }
    }

    override suspend fun uploadPart(
        session: UploadSession,
        part: SignedPart,
        bytes: ByteArray,
    ): UploadedPart =
        mutex.withLock {
            uploadedPartNumbers += part.number
            failNextUploadPart?.let {
                failNextUploadPart = null
                throw it
            }
            partsByKey.getOrPut(session.key) { mutableMapOf() }[part.number] = bytes.copyOf()
            UploadedPart(number = part.number, etag = "etag-${part.number}", size = part.size)
        }

    override suspend fun completeUpload(
        session: UploadSession,
        parts: List<UploadedPart>,
    ): StoredObject =
        mutex.withLock {
            failCompleteUpload?.let { throw it }
            val stored = partsByKey[session.key]
            checkNotNull(stored) { "no parts were ever uploaded for '${session.key}'" }
            check(parts.map { it.number }.toSet() == stored.keys) {
                "completeUpload was asked to assemble ${parts.map { it.number }} but only ${stored.keys} exist"
            }
            StoredObject(
                key = session.key,
                sizeBytes = session.sizeBytes,
                contentType = "application/octet-stream",
                contentHash = ContentHash(HASH),
                updatedAt = Instant.parse(EXPIRES_AT),
            )
        }

    override suspend fun getDownloadUrl(
        key: ObjectKey,
        ttl: Duration,
    ): PresignedUrl = PresignedUrl("${PresignedUrl.LOCAL_SCHEME}$key", Instant.parse(EXPIRES_AT) + 15.minutes)

    override suspend fun delete(key: ObjectKey) {
        mutex.withLock { partsByKey.remove(key) }
    }

    override suspend fun stat(key: ObjectKey): StoredObject? = null

    /**
     * Simulates a part the *server* already acknowledged before this test process existed — the
     * scenario a resumed upload actually faces, where the previous run's [uploadPart] call landed
     * on a different in-memory instance (or a real provider) than the one this test constructs.
     */
    suspend fun seedAcknowledgedPart(
        key: ObjectKey,
        number: Int,
        size: Long,
    ) {
        mutex.withLock { partsByKey.getOrPut(key) { mutableMapOf() }[number] = ByteArray(size.toInt()) }
    }

    private companion object {
        const val EXPIRES_AT = "2026-01-01T00:00:00Z"
        val HASH = "a".repeat(64)
    }
}
