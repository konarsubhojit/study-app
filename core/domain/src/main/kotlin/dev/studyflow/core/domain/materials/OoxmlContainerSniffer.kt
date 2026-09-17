package dev.studyflow.core.domain.materials

/**
 * Distinguishes a Word, Excel or PowerPoint document from any other ZIP container by the names of
 * its package entries (issue #37).
 *
 * ### Why entry names, and not the declared content type
 *
 * An Office Open XML file — `.docx`, `.xlsx`, `.pptx` — is a ZIP archive whose parts follow a fixed
 * convention: the main document always lives under a top-level `word/`, `xl/` or `ppt/` directory.
 * That directory name is stored as plain, uncompressed ASCII in every ZIP local file header,
 * whether or not the entry's *contents* are compressed — so it can be read directly out of the
 * bytes already sniffed for [FileSignatureSniffer], without inflating a single entry. Reading
 * `[Content_Types].xml` instead would mean decompressing it, which this deliberately avoids.
 *
 * ### Why it is bounded and safe
 *
 * [sniff] never does its own I/O: it is handed the same fixed-size window [MaterialImporter]
 * already captured while streaming the copy, and it only walks that window once, looking for the
 * local file header signature at each position. No entry is decompressed, so there is no
 * zip-bomb-style amplification to guard against, and memory use is exactly the window's size
 * regardless of how large — or how deeply nested — the archive actually is.
 */
public object OoxmlContainerSniffer {
    /**
     * Identifies a Word, Excel or PowerPoint document from its ZIP package entries.
     *
     * @param window a buffer whose first [length] bytes are the start of the file.
     * @return the specific office MIME type, or `null` when no recognised part name was found in
     *   the window — which only means the answer wasn't in the bytes already sniffed, not that the
     *   file is definitely a plain ZIP.
     */
    public fun sniff(
        window: ByteArray,
        length: Int,
    ): String? {
        val bounded = length.coerceIn(0, window.size)
        var offset = 0
        while (offset + LOCAL_FILE_HEADER_SIGNATURE.size <= bounded) {
            if (matchesSignatureAt(window, offset)) {
                mimeForEntryAt(window, offset, bounded)?.let { return it }
            }
            offset += 1
        }
        return null
    }

    private fun matchesSignatureAt(
        window: ByteArray,
        offset: Int,
    ): Boolean = LOCAL_FILE_HEADER_SIGNATURE.indices.all { window[offset + it] == LOCAL_FILE_HEADER_SIGNATURE[it] }

    /**
     * Reads the entry name that follows a local file header found at [offset], per the ZIP local
     * file header layout: a 30-byte fixed header, immediately followed by the file name.
     */
    private fun mimeForEntryAt(
        window: ByteArray,
        offset: Int,
        bounded: Int,
    ): String? {
        val name = entryNameAt(window, offset, bounded) ?: return null
        return MARKERS.firstOrNull { (prefix, _) -> name.startsWith(prefix) }?.second
    }

    private fun entryNameAt(
        window: ByteArray,
        offset: Int,
        bounded: Int,
    ): String? {
        val nameLengthIndex = offset + NAME_LENGTH_OFFSET
        val nameLengthReadable = nameLengthIndex + Short.SIZE_BYTES <= bounded
        val nameLength = if (nameLengthReadable) littleEndianUShort(window, nameLengthIndex) else -1
        val nameStart = offset + LOCAL_FILE_HEADER_FIXED_LENGTH
        val nameEnd = nameStart + nameLength
        val nameInBounds = nameLength in 1..MAX_NAME_LENGTH && nameEnd <= bounded
        return if (nameInBounds) String(window, nameStart, nameLength, Charsets.US_ASCII) else null
    }

    private fun littleEndianUShort(
        window: ByteArray,
        index: Int,
    ): Int = (window[index].toInt() and BYTE_MASK) or ((window[index + 1].toInt() and BYTE_MASK) shl Byte.SIZE_BITS)

    private val LOCAL_FILE_HEADER_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    // Offsets within a ZIP local file header (APPNOTE.TXT §4.3.7): a 30-byte fixed part, with the
    // 2-byte little-endian file-name length at offset 26, immediately preceding it.
    private const val NAME_LENGTH_OFFSET = 26
    private const val LOCAL_FILE_HEADER_FIXED_LENGTH = 30

    // Longer than any of this file's marker prefixes plus a plausible part path; guards against
    // treating header bytes that merely resemble a signature as a plausible file name.
    private const val MAX_NAME_LENGTH = 256
    private const val BYTE_MASK = 0xFF

    private val MARKERS: List<Pair<String, String>> =
        listOf(
            "word/" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xl/" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ppt/" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        )

    /** How much of a ZIP-signed file is worth capturing to reach its first real package parts. */
    public const val SCAN_WINDOW_BYTES: Int = 16 * 1024
}
