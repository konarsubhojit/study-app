package dev.studyflow.core.storage

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The only way the app touches remote bytes (issue #36).
 *
 * Everything above this interface — the materials catalogue, the upload worker, the previewers —
 * is written against opaque [ObjectKey]s and short-lived [PresignedUrl]s, never against a bucket
 * name, a region, an SDK type or a credential. That is what makes the provider decision in
 * `docs/adr/0010-storage-provider.md` reversible: swapping Supabase Storage for an S3-compatible
 * bucket is a different implementation of these six functions and one line of DI wiring.
 *
 * **No implementation may hold a cloud provider credential.** The client authenticates to our own
 * BFF and receives URLs that expire, so an extracted APK yields nothing an ordinary signed-in user
 * does not already have.
 *
 * Implementations throw [ObjectStoreException] — and nothing else — for anticipated failures, so a
 * caller can tell "retry this later" from "this will never work".
 */
public interface ObjectStore {
    /**
     * Opens an upload and returns the plan the caller must follow.
     *
     * Part boundaries come from the store rather than from the caller so that a plan recomputed
     * after a process death matches the original and only the missing parts are resent.
     *
     * @throws ObjectStoreException when the upload cannot be opened.
     */
    public suspend fun initUpload(request: UploadRequest): UploadSession

    /**
     * Uploads the bytes of a single [part] of [session].
     *
     * @param bytes exactly the [SignedPart.size] bytes at [SignedPart.offset] in the file.
     * @return the receipt [completeUpload] needs for this part.
     * @throws ObjectStoreException when the part cannot be stored.
     */
    public suspend fun uploadPart(
        session: UploadSession,
        part: SignedPart,
        bytes: ByteArray,
    ): UploadedPart

    /**
     * Assembles [parts] into the finished object and returns what the store now holds.
     *
     * @throws ObjectStoreException when a part is missing, or when the assembled object does not
     *   match [UploadRequest.contentHash].
     */
    public suspend fun completeUpload(
        session: UploadSession,
        parts: List<UploadedPart>,
    ): StoredObject

    /**
     * Mints a short-lived URL the platform downloader can fetch [key] from.
     *
     * @param ttl how long the URL should stay valid; a store may shorten it, never lengthen it.
     * @throws ObjectStoreException.NotFound when the store holds nothing under [key].
     * @throws ObjectStoreException when the URL could not be minted.
     */
    public suspend fun getDownloadUrl(
        key: ObjectKey,
        ttl: Duration = DEFAULT_DOWNLOAD_TTL,
    ): PresignedUrl

    /**
     * Removes [key].
     *
     * Deleting something that is not there succeeds: a delete replayed after a crash must not
     * strand the caller's queue.
     *
     * @throws ObjectStoreException when the delete could not be carried out.
     */
    public suspend fun delete(key: ObjectKey)

    /**
     * Metadata for [key], or `null` when the store holds nothing under it.
     *
     * Content addressing makes this the cheap half of an upload: a file whose digest the store
     * already has needs no bytes sent at all.
     *
     * @throws ObjectStoreException when the store could not be queried.
     */
    public suspend fun stat(key: ObjectKey): StoredObject?

    public companion object {
        /** Long enough to start a download on a slow link, short enough to be useless if it leaks. */
        public val DEFAULT_DOWNLOAD_TTL: Duration = 15.minutes

        /** A signed URL is a bearer credential and must never remain usable for hours or days. */
        public val MAX_PRESIGNED_URL_TTL: Duration = 15.minutes
    }
}
