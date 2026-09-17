package dev.studyflow.core.domain.materials

/**
 * A file type recognised from its leading bytes rather than from anyone's say-so (issue #37).
 *
 * @property mimeType the canonical MIME type this signature stands for.
 */
public enum class FileSignature(
    public val mimeType: String,
) {
    PNG("image/png"),
    JPEG("image/jpeg"),
    GIF("image/gif"),
    BMP("image/bmp"),
    WEBP("image/webp"),
    PDF("application/pdf"),
    ZIP("application/zip"),
    GZIP("application/gzip"),
    SEVEN_ZIP("application/x-7z-compressed"),
    RAR("application/x-rar-compressed"),
    MP3("audio/mpeg"),
    OGG("audio/ogg"),
    WAV("audio/wav"),
    FLAC("audio/flac"),
    MP4("video/mp4"),
    ;

    public companion object {
        /** No signature needs more than this many leading bytes to be recognised. */
        public const val MAX_HEADER_BYTES: Int = 32
    }
}

/**
 * Recognises a file's true type from its magic bytes.
 *
 * ### Why this exists
 *
 * A content resolver reports whatever the source app declared, and a file name carries whatever
 * extension its author typed — neither is a claim about the bytes themselves. A `.docx` that is
 * actually a renamed PDF, or a photo shared with no extension at all, both sail past a check that
 * trusts either one. Sniffing the first handful of bytes tells the truth a mislabelled or
 * unlabelled file cannot: what container format it actually is.
 *
 * ### Why it is cheap
 *
 * Every signature here is decided from at most [FileSignature.MAX_HEADER_BYTES] bytes, so a caller
 * streaming a multi-gigabyte import can sniff it from the first read of the copy loop with no extra
 * pass over the file.
 *
 * ### What it deliberately does not do
 *
 * Office documents (`.docx`, `.xlsx`, `.pptx`) are themselves ZIP containers, so a sniff of one
 * with a generic or missing declared type yields [FileSignature.ZIP] rather than the specific
 * office format — still a correction of a false claim, just a coarser one. Distinguishing them
 * would mean reading the archive's central directory, which is not "cheap" in the sense this object
 * promises.
 */
public object FileSignatureSniffer {
    /**
     * Identifies the file type from its header.
     *
     * @param header a buffer whose first [length] bytes are the start of the file.
     * @return the recognised signature, or `null` when no known signature matches — which is not
     *   evidence of anything, just the absence of a cheap answer.
     */
    public fun sniff(
        header: ByteArray,
        length: Int,
    ): FileSignature? {
        val bounded = length.coerceIn(0, header.size)
        return SIGNATURES.firstOrNull { it.matches(header, bounded) }?.signature
    }

    private class Rule(
        val signature: FileSignature,
        val match: (ByteArray, Int) -> Boolean,
    ) {
        fun matches(
            header: ByteArray,
            length: Int,
        ): Boolean = match(header, length)
    }

    private fun magic(
        signature: FileSignature,
        vararg bytes: Int,
        offset: Int = 0,
    ): Rule =
        Rule(signature) { header, length ->
            offset + bytes.size <= length && bytes.indices.all { header[offset + it] == bytes[it].toByte() }
        }

    private fun ascii(
        signature: FileSignature,
        text: String,
        offset: Int = 0,
    ): Rule =
        Rule(signature) { header, length ->
            offset + text.length <= length &&
                text.indices.all { header[offset + it] == text[it].code.toByte() }
        }

    private fun riffOf(
        signature: FileSignature,
        formType: String,
    ): Rule =
        Rule(signature) { header, length ->
            length >= RIFF_FORM_TYPE_OFFSET + RIFF_FORM_TYPE_LENGTH &&
                ascii(signature, "RIFF").matches(header, length) &&
                formType.indices.all { header[RIFF_FORM_TYPE_OFFSET + it] == formType[it].code.toByte() }
        }

    private val SIGNATURES: List<Rule> =
        listOf(
            magic(FileSignature.PNG, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            magic(FileSignature.JPEG, 0xFF, 0xD8, 0xFF),
            ascii(FileSignature.GIF, "GIF87a"),
            ascii(FileSignature.GIF, "GIF89a"),
            ascii(FileSignature.BMP, "BM"),
            riffOf(FileSignature.WEBP, "WEBP"),
            riffOf(FileSignature.WAV, "WAVE"),
            ascii(FileSignature.PDF, "%PDF-"),
            magic(FileSignature.ZIP, 0x50, 0x4B, 0x03, 0x04),
            magic(FileSignature.ZIP, 0x50, 0x4B, 0x05, 0x06),
            magic(FileSignature.GZIP, 0x1F, 0x8B),
            magic(FileSignature.SEVEN_ZIP, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C),
            ascii(FileSignature.RAR, "Rar!\u001A\u0007"),
            ascii(FileSignature.MP3, "ID3"),
            mp3FrameSync(),
            ascii(FileSignature.OGG, "OggS"),
            ascii(FileSignature.FLAC, "fLaC"),
            ascii(FileSignature.MP4, "ftyp", offset = 4),
        )

    /**
     * Untagged MPEG audio has no magic string, only a frame header: eleven set sync bits followed
     * by a layer/version nibble that is never `0000` or `1111` on a real stream.
     */
    private fun mp3FrameSync(): Rule =
        Rule(FileSignature.MP3) { header, length ->
            length >= MP3_FRAME_HEADER_BYTES &&
                (header[0].toInt() and BYTE_MASK) == BYTE_MASK &&
                (header[1].toInt() and MP3_SYNC_BITS_MASK) == MP3_SYNC_BITS_MASK &&
                (header[1].toInt() and MP3_LAYER_BITS_MASK) != 0
        }

    private const val RIFF_FORM_TYPE_OFFSET = 8
    private const val RIFF_FORM_TYPE_LENGTH = 4
    private const val MP3_FRAME_HEADER_BYTES = 2
    private const val BYTE_MASK = 0xFF
    private const val MP3_SYNC_BITS_MASK = 0xE0
    private const val MP3_LAYER_BITS_MASK = 0x18
}
