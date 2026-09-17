package dev.studyflow.core.domain.materials

/**
 * One part of a resumable upload the object store has already acknowledged (issue #38).
 *
 * A domain-level mirror of `dev.studyflow.core.storage.UploadedPart` rather than a reuse of it:
 * `:core:domain` cannot depend on `:core:storage` (that module depends back on this one), and the
 * upload worker is what maps between the two shapes.
 */
public data class CompletedUploadPart(
    val number: Int,
    val etag: String,
    val sizeBytes: Long,
) {
    init {
        require(number >= 1) { "CompletedUploadPart.number is 1-based, was $number" }
        require(sizeBytes >= 0) { "CompletedUploadPart.sizeBytes must not be negative, was $sizeBytes" }
    }
}

/**
 * Durable memory of which parts of a material's upload the object store has already acknowledged,
 * keyed by material id (issue #38).
 *
 * Part progress has to outlive the process: WorkManager, and the process it runs in, can be killed
 * at any point during a multi-hundred-megabyte transfer over flaky campus wi-fi. Persisting each
 * part's receipt as soon as it arrives — rather than only at the end — is what lets the next run
 * resend only the parts that never got acknowledged instead of starting the whole file over.
 */
public interface UploadProgressStore {
    /** Parts already acknowledged for [materialId], in no particular order. */
    public suspend fun completedParts(materialId: String): List<CompletedUploadPart>

    /** Persists that [part] has been acknowledged, before the next part is attempted. */
    public suspend fun recordCompletedPart(
        materialId: String,
        part: CompletedUploadPart,
    )

    /**
     * Drops all progress for [materialId].
     *
     * Called once the upload finishes — successfully or permanently — so a finished material does
     * not carry an ever-growing set of stale part receipts.
     */
    public suspend fun clear(materialId: String)
}
