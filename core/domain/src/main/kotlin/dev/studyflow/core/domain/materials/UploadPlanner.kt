package dev.studyflow.core.domain.materials

import dev.studyflow.core.model.ContentHash

/**
 * Splits a file into the parts of a resumable multipart upload.
 *
 * ### Why chunking is not optional
 *
 * Study materials are lecture recordings and scanned textbooks, and they are uploaded over student
 * wi-fi and phone tethering. A single `PUT` of a 700 MB video will be interrupted, and without
 * parts the only recovery is to start again — which, on a metered connection, is how an app gets
 * uninstalled. Parts make failure cheap: retry the 8 MB that failed, not the 650 MB that worked.
 *
 * ### Why the plan is a pure function of size
 *
 * Part boundaries are derived from the file length, so the same file always produces the same plan.
 * Combined with the content hash, that makes an interrupted upload resumable across process death
 * and app restarts without storing any progress beyond "which part numbers completed".
 */
public object UploadPlanner {
    /**
     * Builds the part layout for a file.
     *
     * @param totalBytes size of the file. Zero is legal — an empty file is still a file the user
     *   chose to keep — and produces a single empty part.
     * @param contentHash SHA-256 computed before the upload starts, used to make the upload
     *   idempotent and to verify the object afterwards.
     */
    public fun plan(
        totalBytes: Long,
        contentHash: ContentHash,
        limits: MultipartLimits = MultipartLimits.S3_COMPATIBLE,
    ): UploadPlan {
        require(totalBytes >= 0) { "totalBytes must not be negative, was $totalBytes" }

        val partSize = choosePartSize(totalBytes, limits)
        val parts =
            buildList {
                var offset = 0L
                var number = 1
                do {
                    val size = minOf(partSize, totalBytes - offset)
                    add(UploadPart(number = number, offset = offset, size = size))
                    offset += size
                    number += 1
                } while (offset < totalBytes)
            }
        return UploadPlan(contentHash = contentHash, totalBytes = totalBytes, parts = parts)
    }

    /**
     * Picks the smallest legal part size for the file.
     *
     * Object stores impose both a floor on part size and a ceiling on part count, and the two
     * conflict for large files: 700 MB at the 5 MiB minimum is fine, but a 200 GB file would need
     * 40,000 parts. Scaling the part size up just enough to stay inside the part limit keeps
     * retries cheap for ordinary files while still supporting very large ones.
     */
    private fun choosePartSize(
        totalBytes: Long,
        limits: MultipartLimits,
    ): Long {
        val required = ceilDiv(totalBytes, limits.maxParts)
        return maxOf(limits.minPartSizeBytes, required).coerceAtMost(limits.maxPartSizeBytes)
    }

    private fun ceilDiv(
        value: Long,
        divisor: Int,
    ): Long = (value + divisor - 1) / divisor
}

/**
 * Object-store constraints on multipart uploads.
 *
 * @property minPartSizeBytes every part except the last must be at least this large.
 * @property maxPartSizeBytes upper bound on a single part.
 * @property maxParts upper bound on the number of parts in one upload.
 */
public data class MultipartLimits(
    val minPartSizeBytes: Long,
    val maxPartSizeBytes: Long,
    val maxParts: Int,
) {
    init {
        require(minPartSizeBytes > 0) { "minPartSizeBytes must be positive" }
        require(maxPartSizeBytes >= minPartSizeBytes) { "maxPartSizeBytes must be at least minPartSizeBytes" }
        require(maxParts > 0) { "maxParts must be positive" }
    }

    public companion object {
        private const val MIB = 1024L * 1024L

        /** The limits shared by S3, R2 and MinIO, which is what the BFF will sit in front of. */
        public val S3_COMPATIBLE: MultipartLimits =
            MultipartLimits(
                minPartSizeBytes = 8 * MIB,
                maxPartSizeBytes = 512 * MIB,
                maxParts = 10_000,
            )
    }
}

/** One slice of a file, uploaded against its own presigned URL. */
public data class UploadPart(
    val number: Int,
    val offset: Long,
    val size: Long,
) {
    init {
        require(number >= 1) { "part numbers are 1-based, was $number" }
        require(offset >= 0) { "offset must not be negative, was $offset" }
        require(size >= 0) { "size must not be negative, was $size" }
    }

    /** Exclusive end offset, useful for a ranged read of the source file. */
    public val endExclusive: Long get() = offset + size
}

/**
 * The complete layout of one upload.
 *
 * @property contentHash digest of the whole file, verified against the stored object at the end.
 *   An upload that completes but hashes differently is a corrupted upload, not a successful one.
 */
public data class UploadPlan(
    val contentHash: ContentHash,
    val totalBytes: Long,
    val parts: List<UploadPart>,
) {
    init {
        require(parts.isNotEmpty()) { "an upload plan needs at least one part" }
        require(parts.sumOf { it.size } == totalBytes) {
            "parts cover ${parts.sumOf { it.size }} bytes but the file is $totalBytes"
        }
    }

    /** Parts still to send, given the part numbers the server has already acknowledged. */
    public fun remaining(completedParts: Set<Int>): List<UploadPart> = parts.filterNot { it.number in completedParts }

    /** Bytes confirmed stored, for a determinate progress indicator that never goes backwards. */
    public fun uploadedBytes(completedParts: Set<Int>): Long =
        parts.filter { it.number in completedParts }.sumOf { it.size }

    /** True when every part has been acknowledged and only the final verification remains. */
    public fun isComplete(completedParts: Set<Int>): Boolean = parts.all { it.number in completedParts }
}
