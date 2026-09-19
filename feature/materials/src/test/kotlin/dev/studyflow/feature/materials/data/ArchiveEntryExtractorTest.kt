package dev.studyflow.feature.materials.data

import dev.studyflow.core.domain.materials.ArchiveEntryVerdict
import dev.studyflow.core.domain.materials.ArchiveExtractionResult
import dev.studyflow.core.domain.materials.ArchiveListing
import dev.studyflow.core.domain.materials.ArchiveReader
import dev.studyflow.core.domain.materials.ArchiveRejection
import dev.studyflow.core.testing.coroutines.testDispatcherProvider
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@DisplayName("ArchiveEntryExtractor")
class ArchiveEntryExtractorTest {
    @TempDir
    lateinit var archivesRoot: File

    @Test
    fun `lists and extracts an entry into a per-material subfolder`() =
        runTest {
            val archiveFile = storedZipFile("cached.zip", "notes.txt" to "hello world".toByteArray())
            val extractor = extractor()

            val listing = extractor.list(archiveFile) as ArchiveListing.Listed
            val entry = listing.verdict.accepted.single { it.name == "notes.txt" }

            val result = extractor.extract(archiveFile, materialId = "material-1", entry = entry)

            val extracted = result as? ArchiveExtractionResult.Extracted ?: error("expected Extracted but was $result")
            assertEquals("hello world", extracted.file.readText())
            assertEquals(File(archivesRoot, "material-1/notes.txt"), extracted.file)
        }

    @Test
    fun `two materials never share an extraction subfolder`() =
        runTest {
            val archiveFile = storedZipFile("cached.zip", "notes.txt" to "one".toByteArray())
            val extractor = extractor()
            val listing = extractor.list(archiveFile) as ArchiveListing.Listed
            val entry = listing.verdict.accepted.single()

            val first = extractor.extract(archiveFile, "material-1", entry) as ArchiveExtractionResult.Extracted
            val second = extractor.extract(archiveFile, "material-2", entry) as ArchiveExtractionResult.Extracted

            assertTrue(first.file.path != second.file.path)
            assertEquals("one", first.file.readText())
            assertEquals("one", second.file.readText())
        }

    @Test
    fun `a rejected entry is not offered for extraction`() =
        runTest {
            val archiveFile = storedZipFile("cached.zip", "../../evil.txt" to "malicious".toByteArray())
            val extractor = extractor()

            val listing = extractor.list(archiveFile) as ArchiveListing.Listed

            assertTrue(listing.verdict.accepted.isEmpty())
            assertEquals(
                ArchiveRejection.PATH_TRAVERSAL,
                listing.verdict.rejected
                    .single()
                    .reason,
            )
        }

    private fun TestScope.extractor(): ArchiveEntryExtractor =
        ArchiveEntryExtractor(
            reader = ArchiveReader(testDispatcherProvider()),
            archivesDirectory = { archivesRoot },
        )

    private fun storedZipFile(
        fileName: String,
        vararg entries: Pair<String, ByteArray>,
    ): File {
        val file = File(archivesRoot, fileName)
        ZipOutputStream(file.outputStream()).use { zip ->
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
        return file
    }
}
