package dev.studyflow.core.storage

import java.security.MessageDigest
import java.util.Base64

/**
 * The per-part digests a verifying provider needs before it will sign an upload.
 *
 * S3's `x-amz-checksum-sha256` is the *base64* SHA-256 of the part's bytes (44 characters, one
 * `=` of padding) rather than the lower-case hex [dev.studyflow.core.model.ContentHash] uses for
 * the whole file, so the two are computed separately.
 */
public object PartChecksums {
    /** Base64 SHA-256 of [bytes], the format `x-amz-checksum-sha256` and the BFF expect. */
    public fun sha256Base64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
}
