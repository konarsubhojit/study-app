package dev.studyflow.core.domain.materials

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("FileSignatureSniffer")
class FileSignatureSnifferTest {
    @Test
    fun `recognises a PNG regardless of what it is named`() {
        val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00)

        assertEquals(FileSignature.PNG, FileSignatureSniffer.sniff(header, header.size))
    }

    @Test
    fun `recognises a JPEG`() {
        val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        assertEquals(FileSignature.JPEG, FileSignatureSniffer.sniff(header, header.size))
    }

    @Test
    fun `recognises a PDF whatever extension the file was given`() {
        val header = "%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3".toByteArray(Charsets.ISO_8859_1)

        assertEquals(FileSignature.PDF, FileSignatureSniffer.sniff(header, header.size))
    }

    @Test
    fun `recognises a ZIP-family container`() {
        val header = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)

        assertEquals(FileSignature.ZIP, FileSignatureSniffer.sniff(header, header.size))
    }

    @Test
    fun `recognises a WEBP by its RIFF form type, not just its RIFF header`() {
        val header = "RIFF????WEBPVP8 ".toByteArray(Charsets.US_ASCII)

        assertEquals(FileSignature.WEBP, FileSignatureSniffer.sniff(header, header.size))
    }

    @Test
    fun `does not confuse a WAV with a WEBP even though both start with RIFF`() {
        val header = "RIFF????WAVEfmt ".toByteArray(Charsets.US_ASCII)

        assertEquals(FileSignature.WAV, FileSignatureSniffer.sniff(header, header.size))
    }

    @Test
    fun `recognises an MP4-family container by its ftyp box`() {
        val header = byteArrayOf(0x00, 0x00, 0x00, 0x18) + "ftypisom".toByteArray(Charsets.US_ASCII)

        assertEquals(FileSignature.MP4, FileSignatureSniffer.sniff(header, header.size))
    }

    @Test
    fun `recognises untagged MP3 by its frame sync bits`() {
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)

        assertEquals(FileSignature.MP3, FileSignatureSniffer.sniff(header, header.size))
    }

    @Test
    fun `plain text has no signature and is reported as unknown rather than guessed`() {
        val header = "Dear diary, today in Chemistry...".toByteArray(Charsets.US_ASCII)

        assertNull(FileSignatureSniffer.sniff(header, header.size))
    }

    @Test
    fun `a header shorter than any known signature is unknown, not a false match`() {
        val header = byteArrayOf(0x89.toByte(), 0x50)

        assertNull(FileSignatureSniffer.sniff(header, header.size))
    }

    @Test
    fun `only the declared length is inspected, ignoring stale bytes left over in the buffer`() {
        val header = ByteArray(FileSignature.MAX_HEADER_BYTES)
        "%PDF-".toByteArray(Charsets.US_ASCII).copyInto(header)

        assertNull(FileSignatureSniffer.sniff(header, length = 2))
    }
}
