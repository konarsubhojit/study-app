package dev.studyflow.core.storage

import dev.studyflow.core.domain.materials.UploadPart
import dev.studyflow.core.domain.materials.UploadPlan
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailSpec
import dev.studyflow.core.model.ContentHash
import kotlin.time.Instant

/**
 * Where an object lives, as far as the app is concerned (issue #36).
 *
 * The value is opaque: the app derives it from content (`materials/<sha256>`, or
 * `thumbnails/<sha256>/<edge>`) and hands it back unchanged. It is deliberately *not* a URL, a
 * bucket path or anything else that would leak the provider's shape into the layers above
 * [ObjectStore].
 *
 * The rejected characters are the ones that turn a key into a path traversal on a store backed by a
 * filesystem — the same class of bug as zip slip, and worth refusing at the type rather than hoping
 * every adapter remembers.
 */
@JvmInline
public value class ObjectKey(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ObjectKey must not be blank" }
        require(value.length <= MAX_LENGTH) { "ObjectKey must be at most $MAX_LENGTH characters" }
        require(!value.startsWith('/')) { "ObjectKey must be relative, was '$value'" }
        require('\\' !in value) { "ObjectKey must not contain a backslash, was '$value'" }
        require(value.none { it.isISOControl() }) { "ObjectKey must not contain control characters" }
        require(value.split('/').none { it == "." || it == ".." }) {
            "ObjectKey must not contain '.' or '..' segments, was '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private const val MAX_LENGTH = 1024

        /** The content-addressed key of a material's original bytes. */
        public fun ofMaterial(contentHash: ContentHash): ObjectKey = ObjectKey("materials/${contentHash.hex}")

        /**
         * The content-addressed key of a server-rendered thumbnail (issue #42).
         *
         * Derived from the *original's* digest and the requested edge length, so a thumbnail the
         * backend renders for a format no device could decode (issue #7) is found by every client
         * that holds the same file, and is rendered once however many users uploaded it.
         *
         * The edge arrives as a [ThumbnailSpec] rather than a bare number so that the sizes this
         * key can name are exactly the sizes the on-device pipeline can render.
         */
        public fun ofThumbnail(
            contentHash: ContentHash,
            spec: ThumbnailSpec,
        ): ObjectKey = ObjectKey("thumbnails/${contentHash.hex}/${spec.maxEdgePx}")
    }
}

/**
 * A URL that carries its own, expiring authorisation.
 *
 * Bytes travel directly between the device and the storage provider over one of these; our BFF
 * signs the URL but never proxies the payload, which is what keeps egress off the API tier.
 *
 * @property expiresAt after this instant the URL is worthless — to us and to anyone who copied it.
 */
public data class PresignedUrl(
    val url: String,
    val expiresAt: Instant,
) {
    init {
        require(url.startsWith("http://") || url.startsWith("https://") || url.startsWith(LOCAL_SCHEME)) {
            "PresignedUrl.url must be an absolute http(s) or $LOCAL_SCHEME URL, was '$url'"
        }
    }

    /** Whether the URL is still usable at [now]. */
    public fun isValidAt(now: Instant): Boolean = now < expiresAt

    public companion object {
        /** Scheme used by adapters that never leave the device, so a fake URL cannot be dialled. */
        public const val LOCAL_SCHEME: String = "studyflow-local://"
    }
}

/**
 * Everything the store needs to open an upload.
 *
 * [contentHash] is not optional: the digest is computed before the first byte is sent, it *is* the
 * key, and it is what turns "did this arrive intact?" into a comparison rather than a hope
 * (ADR 0005).
 */
public data class UploadRequest(
    val key: ObjectKey,
    val sizeBytes: Long,
    val contentType: String,
    val contentHash: ContentHash,
) {
    init {
        require(sizeBytes >= 0) { "UploadRequest.sizeBytes must not be negative, was $sizeBytes" }
        require(contentType.isNotBlank()) { "UploadRequest.contentType must not be blank" }
    }

    public companion object {
        /** The ordinary case: a material is stored under its own digest. */
        public fun ofMaterial(
            contentHash: ContentHash,
            sizeBytes: Long,
            contentType: String,
        ): UploadRequest =
            UploadRequest(
                key = ObjectKey.ofMaterial(contentHash),
                sizeBytes = sizeBytes,
                contentType = contentType,
                contentHash = contentHash,
            )
    }
}

/**
 * An upload in progress: the key, the provider's handle for it, and a signed URL per part.
 *
 * The session is a value rather than hidden state so that it can be persisted and resumed after a
 * process death, which for a 700 MB lecture recording on student wi-fi is the normal case. The part
 * layout comes from [dev.studyflow.core.domain.materials.UploadPlanner], so a plan recomputed after
 * a restart matches the interrupted one and only the missing parts are resent.
 */
public data class UploadSession(
    val key: ObjectKey,
    val uploadId: String,
    val parts: List<SignedPart>,
    val expiresAt: Instant,
) {
    init {
        require(uploadId.isNotBlank()) { "UploadSession.uploadId must not be blank" }
        require(parts.isNotEmpty()) { "UploadSession must have at least one part" }
        require(parts.map { it.number } == parts.indices.map { it + 1 }) {
            "UploadSession parts must be numbered 1..${parts.size} in order"
        }
    }

    /** Total number of bytes this session will transfer. */
    public val sizeBytes: Long get() = parts.sumOf { it.size }

    public companion object {
        /**
         * Signs every part of [plan].
         *
         * @param urlFor mints the destination of one part; the provider's business, never the
         *   caller's.
         */
        public fun of(
            key: ObjectKey,
            uploadId: String,
            plan: UploadPlan,
            expiresAt: Instant,
            urlFor: (UploadPart) -> PresignedUrl,
        ): UploadSession =
            UploadSession(
                key = key,
                uploadId = uploadId,
                parts = plan.parts.map { part -> SignedPart(part, urlFor(part)) },
                expiresAt = expiresAt,
            )
    }
}

/**
 * One planned [UploadPart] plus the expiring URL its bytes go to.
 *
 * The split is deliberate: *which* bytes form a part is domain logic the offline catalogue needs
 * too, while *where* they go is a provider detail that only lives as long as the signature.
 */
public data class SignedPart(
    val part: UploadPart,
    val url: PresignedUrl,
) {
    /** 1-based index of this part within the upload. */
    public val number: Int get() = part.number

    /** Where the part starts in the file. */
    public val offset: Long get() = part.offset

    /** How many bytes it covers; zero only for the single part of an empty file. */
    public val size: Long get() = part.size
}

/**
 * Proof that a part arrived, to be handed back to [ObjectStore.completeUpload].
 *
 * @property etag the provider's identifier for the stored part; S3-compatible stores need the exact
 *   value back when the object is assembled.
 */
public data class UploadedPart(
    val number: Int,
    val etag: String,
    val size: Long,
) {
    init {
        require(number >= 1) { "UploadedPart.number is 1-based, was $number" }
        require(size >= 0) { "UploadedPart.size must not be negative, was $size" }
    }
}

/** What the store holds under a key. */
public data class StoredObject(
    val key: ObjectKey,
    val sizeBytes: Long,
    val contentType: String,
    val contentHash: ContentHash,
    val updatedAt: Instant,
) {
    init {
        require(sizeBytes >= 0) { "StoredObject.sizeBytes must not be negative, was $sizeBytes" }
    }
}
