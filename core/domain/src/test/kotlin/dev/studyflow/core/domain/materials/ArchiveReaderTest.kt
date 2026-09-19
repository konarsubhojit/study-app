package dev.studyflow.core.domain.materials

import dev.studyflow.core.testing.coroutines.testDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ArchiveReader")
class ArchiveReaderTest {
    @TempDir
    lateinit var destinationRoot: File

    @Test
    fun `a well-formed small zip lists and extracts correctly, contents match`() =
        runTest {
            val zip =
                storedZip(
                    "notes.txt" to "hello world".toByteArray(),
                    "folder/nested.txt" to "nested content".toByteArray(),
                )
            val reader = ArchiveReader(testDispatcherProvider())

            val listing = reader.list { ByteArrayInputStream(zip) } as ArchiveListing.Listed
            assertTrue(listing.verdict.isClean, "nothing should be rejected")
            assertEquals(
                setOf("notes.txt", "folder/nested.txt"),
                listing.verdict.accepted
                    .map { it.path }
                    .toSet(),
            )

            val entry = listing.verdict.accepted.single { it.name == "notes.txt" }
            val result = reader.extract({ ByteArrayInputStream(zip) }, entry, destinationRoot)

            val extracted = result as? ArchiveExtractionResult.Extracted ?: error("expected Extracted but was $result")
            assertEquals("hello world", extracted.file.readText())
            assertEquals(File(destinationRoot, "notes.txt"), extracted.file)
        }

    @Test
    fun `a stream truncated mid-entry is a clean rejection, not a thrown exception`() =
        runTest {
            // A valid header followed by less content than it declares: readable enough to start,
            // truncated enough that walking past the entry hits a real end-of-stream failure. The
            // whole tail — the rest of the content and the central directory — is simply cut off.
            val entryName = "notes.txt"
            val payload = "hello world".toByteArray()
            val zip = storedZip(entryName to payload)
            val contentStart = LOCAL_FILE_HEADER_FIXED_BYTES + entryName.length
            val truncated = zip.copyOf(contentStart + PARTIAL_CONTENT_BYTES)
            val reader = ArchiveReader(testDispatcherProvider())

            val listing = reader.list { ByteArrayInputStream(truncated) }

            assertEquals(ArchiveListing.Malformed, listing)
        }

    @Test
    fun `extracting from a broken stream fails cleanly instead of throwing`() =
        runTest {
            val reader = ArchiveReader(testDispatcherProvider())
            val entry = ArchiveEntryVerdict.Accepted(name = "notes.txt", path = "notes.txt", declaredSize = 5)

            val result = reader.extract({ ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, entry, destinationRoot)

            assertEquals(ArchiveExtractionResult.Failed, result)
            assertTrue(destinationRoot.listFiles().isNullOrEmpty(), "no partial file should survive")
        }

    @Nested
    @DisplayName("zip slip")
    inner class ZipSlip {
        @Test
        fun `an entry that escapes the root is rejected during listing, not offered for extraction`() =
            runTest {
                val zip =
                    storedZip(
                        "notes.txt" to "safe".toByteArray(),
                        "../../evil.txt" to "malicious".toByteArray(),
                    )
                val reader = ArchiveReader(testDispatcherProvider())

                val listing = reader.list { ByteArrayInputStream(zip) } as ArchiveListing.Listed

                assertEquals(1, listing.verdict.accepted.size)
                assertEquals(
                    ArchiveRejection.PATH_TRAVERSAL,
                    listing.verdict.rejected
                        .single()
                        .reason,
                )
                assertTrue(listing.verdict.accepted.none { it.name == "../../evil.txt" })
            }

        @Test
        fun `extraction refuses a traversal path even if a caller constructs one directly, defence in depth`() =
            runTest {
                val zip = storedZip("../../evil.txt" to "malicious".toByteArray())
                val reader = ArchiveReader(testDispatcherProvider())
                // ArchiveSafety would never itself produce this Accepted; a caller could only reach
                // here by bypassing it entirely, which is exactly the case extraction must also guard.
                val forgedEntry =
                    ArchiveEntryVerdict.Accepted(name = "../../evil.txt", path = "../../evil.txt", declaredSize = 9)
                val nestedRoot = File(destinationRoot, "materials/entry-1")

                val result = reader.extract({ ByteArrayInputStream(zip) }, forgedEntry, nestedRoot)

                assertEquals(ArchiveExtractionResult.Rejected(ArchiveRejection.PATH_TRAVERSAL), result)
                assertTrue(
                    destinationRoot.walkTopDown().none { it.isFile },
                    "nothing must be written outside the destination directory",
                )
            }
    }

    @Nested
    @DisplayName("zip bombs")
    inner class ZipBombs {
        @Test
        fun `an entry with an implausible compression ratio is rejected before it is ever expanded`() =
            runTest {
                // 2 MiB of a single repeated byte deflates to roughly a kilobyte: a real bomb shape,
                // not a mocked one.
                val payload = ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() }
                val zip = deflatedZip("bomb.bin", payload)
                val reader = ArchiveReader(testDispatcherProvider())

                val listing = reader.list { ByteArrayInputStream(zip) } as ArchiveListing.Listed

                assertTrue(listing.verdict.accepted.isEmpty())
                assertEquals(
                    ArchiveRejection.SUSPICIOUS_COMPRESSION_RATIO,
                    listing.verdict.rejected
                        .single()
                        .reason,
                )
            }

        @Test
        fun `a header that lies about a small size is still caught by the running byte count during extraction`() =
            runTest {
                val realPayload = ByteArray(2000) { 'x'.code.toByte() }
                val zip = deflatedZip("lie.bin", realPayload)
                val lyingZip = patchDeclaredUncompressedSize(zip, lie = 5)
                val limits = ArchiveLimits.DEFAULT.copy(maxEntryBytes = 100)
                val reader = ArchiveReader(testDispatcherProvider(), limits)
                // A real `list()` call would itself surface this archive as Malformed — ZipInputStream
                // detects the size/content mismatch as soon as something walks past the entry — so the
                // forged verdict below is what stands in for "a caller trusted the lie a moment too
                // soon"; extraction must not repeat that mistake even then.
                val forgedEntry = ArchiveEntryVerdict.Accepted(name = "lie.bin", path = "lie.bin", declaredSize = 5)

                val result = reader.extract({ ByteArrayInputStream(lyingZip) }, forgedEntry, destinationRoot)

                assertEquals(ArchiveExtractionResult.Rejected(ArchiveRejection.ENTRY_TOO_LARGE), result)
                assertTrue(destinationRoot.listFiles().isNullOrEmpty(), "the partial file must be deleted")
            }

        @Test
        fun `cancelling mid-extraction stops the copy and deletes the partial file`() =
            runTest {
                val payload = ByteArray(10_000) { 'x'.code.toByte() }
                val zip = storedZip("big.bin" to payload)
                val reader = ArchiveReader(testDispatcherProvider())
                val entry = ArchiveEntryVerdict.Accepted(name = "big.bin", path = "big.bin", declaredSize = 10_000)
                var reads = 0

                val result =
                    reader.extract(
                        // Chunked so the copy loop takes several iterations, giving the cancellation
                        // check somewhere to actually land before the whole entry is already copied.
                        opener = { ThrottledInputStream(ByteArrayInputStream(zip), maxBytesPerRead = 500) },
                        entry = entry,
                        destinationRoot = destinationRoot,
                        isCancelled = { (reads++) > 2 },
                    )

                assertEquals(ArchiveExtractionResult.Cancelled, result)
                assertTrue(destinationRoot.listFiles().isNullOrEmpty(), "a cancelled extraction must not leave a file")
            }
    }

    /** Caps every [read] to at most [maxBytesPerRead], so a small source is delivered in several calls. */
    private class ThrottledInputStream(
        private val delegate: InputStream,
        private val maxBytesPerRead: Int,
    ) : InputStream() {
        override fun read(): Int = delegate.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = delegate.read(b, off, minOf(len, maxBytesPerRead))

        override fun close() = delegate.close()
    }

    @Nested
    @DisplayName("delegating to ArchiveSafety")
    inner class DelegatesToArchiveSafety {
        @Test
        fun `duplicate normalised paths are rejected`() =
            runTest {
                val zip =
                    storedZip(
                        "notes/a.md" to "one".toByteArray(),
                        "notes/../notes/a.md" to "two".toByteArray(),
                    )
                val reader = ArchiveReader(testDispatcherProvider())

                val listing = reader.list { ByteArrayInputStream(zip) } as ArchiveListing.Listed

                assertEquals(1, listing.verdict.accepted.size)
                assertEquals(
                    ArchiveRejection.DUPLICATE_PATH,
                    listing.verdict.rejected
                        .single()
                        .reason,
                )
            }

        @Test
        fun `an archive with too many entries is rejected outright`() =
            runTest {
                val zip =
                    storedZip(
                        "a.txt" to "1".toByteArray(),
                        "b.txt" to "2".toByteArray(),
                        "c.txt" to "3".toByteArray(),
                    )
                val reader = ArchiveReader(testDispatcherProvider(), ArchiveLimits.DEFAULT.copy(maxEntries = 2))

                val listing = reader.list { ByteArrayInputStream(zip) } as ArchiveListing.Listed

                assertTrue(listing.verdict.accepted.isEmpty())
                assertTrue(listing.verdict.rejected.all { it.reason == ArchiveRejection.TOO_MANY_ENTRIES })
            }
    }

    private fun storedZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                val checksum = CRC32().apply { update(bytes) }
                val zipEntry =
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        crc = checksum.value
                    }
                zip.putNextEntry(zipEntry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * A single deflated entry with size, compressed size and CRC precomputed and set before
     * writing, so [java.util.zip.ZipOutputStream] does not fall back to a data descriptor — the
     * local header carries real numbers a listing can read without touching the entry's content.
     */
    private fun deflatedZip(
        name: String,
        payload: ByteArray,
    ): ByteArray {
        val checksum = CRC32().apply { update(payload) }
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(payload)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(DEFLATE_BUFFER_BYTES)
        while (!deflater.finished()) {
            val written = deflater.deflate(buffer)
            compressed.write(buffer, 0, written)
        }
        deflater.end()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val zipEntry =
                ZipEntry(name).apply {
                    method = ZipEntry.DEFLATED
                    size = payload.size.toLong()
                    compressedSize = compressed.size().toLong()
                    crc = checksum.value
                }
            zip.putNextEntry(zipEntry)
            zip.write(payload)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * Overwrites the uncompressed-size field of a single-entry, no-data-descriptor local file
     * header produced by [deflatedZip], simulating an archive whose header disagrees with its own
     * content.
     */
    private fun patchDeclaredUncompressedSize(
        zip: ByteArray,
        lie: Int,
    ): ByteArray {
        val patched = zip.copyOf()
        for (i in 0 until Int.SIZE_BYTES) {
            patched[LOCAL_HEADER_UNCOMPRESSED_SIZE_OFFSET + i] = (lie ushr (i * Byte.SIZE_BITS)).toByte()
        }
        return patched
    }

    private companion object {
        const val DEFLATE_BUFFER_BYTES = 8192
        const val PARTIAL_CONTENT_BYTES = 3

        // Signature(4) + version(2) + flags(2) + method(2) + time(2) + date(2) + crc(4) +
        // compressedSize(4) + uncompressedSize(4) + nameLength(2) + extraLength(2): every local
        // file header's fixed prefix, before the entry's own name and content.
        const val LOCAL_FILE_HEADER_FIXED_BYTES = 30

        // Signature(4) + version(2) + flags(2) + method(2) + time(2) + date(2) + crc(4) +
        // compressedSize(4): the uncompressed-size field is the next 4 bytes, little-endian.
        const val LOCAL_HEADER_UNCOMPRESSED_SIZE_OFFSET = 22
    }
}
