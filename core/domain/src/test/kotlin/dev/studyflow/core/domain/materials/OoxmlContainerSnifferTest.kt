package dev.studyflow.core.domain.materials

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OoxmlContainerSniffer")
class OoxmlContainerSnifferTest {
    @Test
    fun `recognises a docx by its word package entry`() {
        val window = zipWindow("[Content_Types].xml", "_rels/.rels", "word/document.xml")

        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            OoxmlContainerSniffer.sniff(window, window.size),
        )
    }

    @Test
    fun `recognises an xlsx by its xl package entry`() {
        val window = zipWindow("[Content_Types].xml", "xl/workbook.xml")

        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            OoxmlContainerSniffer.sniff(window, window.size),
        )
    }

    @Test
    fun `recognises a pptx by its ppt package entry`() {
        val window = zipWindow("[Content_Types].xml", "ppt/presentation.xml")

        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            OoxmlContainerSniffer.sniff(window, window.size),
        )
    }

    @Test
    fun `a plain ZIP with no office directory is reported as unknown rather than guessed`() {
        val window = zipWindow("readme.txt", "photos/vacation.jpg")

        assertNull(OoxmlContainerSniffer.sniff(window, window.size))
    }

    @Test
    fun `only the declared length is inspected, ignoring stale bytes left in a reused buffer`() {
        val window = zipWindow("word/document.xml")

        assertNull(OoxmlContainerSniffer.sniff(window, length = 4))
    }

    @Test
    fun `a truncated entry name past the window bound is not misread as a match`() {
        val full = zipWindow("word/document.xml")
        val truncated = full.copyOf(full.size - 1)

        assertNull(OoxmlContainerSniffer.sniff(truncated, truncated.size))
    }

    /** Builds a byte window containing one ZIP local file header per name, with no entry data. */
    private fun zipWindow(vararg names: String): ByteArray =
        names.fold(ByteArray(0)) { bytes, name -> bytes + localFileHeader(name) }

    private fun localFileHeader(name: String): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x50, 0x4B, 0x03, 0x04) +
            ByteArray(FIXED_HEADER_REMAINDER_BEFORE_NAME_LENGTH) +
            littleEndianShort(nameBytes.size) +
            littleEndianShort(0) +
            nameBytes
    }

    private fun littleEndianShort(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr Byte.SIZE_BITS) and 0xFF).toByte())

    private companion object {
        // Version, flags, method, time, date, crc32, compressed size, uncompressed size: 22 bytes
        // between the signature and the file-name-length field.
        const val FIXED_HEADER_REMAINDER_BEFORE_NAME_LENGTH = 22
    }
}
