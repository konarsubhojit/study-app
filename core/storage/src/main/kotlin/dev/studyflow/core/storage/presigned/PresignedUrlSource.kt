package dev.studyflow.core.storage.presigned

import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.storage.PresignedUrl
import dev.studyflow.core.storage.StoredObject
import dev.studyflow.core.storage.UploadRequest
import dev.studyflow.core.storage.UploadSession
import dev.studyflow.core.storage.UploadedPart
import kotlin.time.Duration

/**
 * The half of [ObjectStore] that must go through our own backend-for-frontend (issue #36, BFF #7).
 *
 * Signing is the only operation that needs a cloud credential, so it is the only operation the
 * client delegates: the BFF authenticates the user, checks their quota and ownership, and answers
 * with URLs that expire. Bytes never pass through it.
 *
 * This is also the seam the provider decision sits behind. A Supabase implementation and an
 * S3/R2 implementation differ entirely inside this interface and not at all above it.
 */
public interface PresignedUrlSource {
    /** Asks the BFF to open an upload and sign a URL per part. */
    public suspend fun createUpload(request: UploadRequest): UploadSession

    /** Tells the BFF every part arrived, so it can assemble and verify the object. */
    public suspend fun finishUpload(
        session: UploadSession,
        parts: List<UploadedPart>,
    ): StoredObject

    /** Asks for a download URL valid for at most [ttl]. */
    public suspend fun downloadUrl(
        key: ObjectKey,
        ttl: Duration,
    ): PresignedUrl

    /** Asks the BFF to delete the object; succeeds when there is nothing to delete. */
    public suspend fun delete(key: ObjectKey)

    /** Metadata for [key], or `null` when the store holds nothing under it. */
    public suspend fun stat(key: ObjectKey): StoredObject?
}
