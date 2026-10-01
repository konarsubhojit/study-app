package dev.studyflow.core.domain.materials

/**
 * What the `storage` Edge Function (`infra/supabase/functions/storage/index.ts`) will accept.
 *
 * These are the server's rules restated on the device so that a file the cloud would refuse is
 * refused when the user picks it — with a sentence they can act on — rather than discovered as an
 * opaque failure halfway through an upload. `CloudStorageContractTest` reads the function source and
 * fails the build if either side changes without the other.
 */
public object CloudStorageLimits {
    private const val MIB = 1024L * 1024L

    /** `PART_SIZE_BYTES`: every part but the last is exactly this long, and the BFF counts parts with it. */
    public const val PART_SIZE_BYTES: Long = 8 * MIB

    /** `MAX_SIZE_BYTES`: the largest object the bucket will hold. */
    public const val MAX_SIZE_BYTES: Long = 50 * MIB

    /** `ALLOWED_MIME_TYPES`: a closed set, compared exactly against the material's MIME type. */
    public val ALLOWED_MIME_TYPES: Set<String> =
        setOf(
            "application/pdf",
            "image/gif",
            "image/jpeg",
            "image/png",
            "image/webp",
            "text/plain",
            "video/mp4",
        )
}
