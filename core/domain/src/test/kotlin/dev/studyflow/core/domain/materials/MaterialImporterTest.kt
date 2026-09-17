package dev.studyflow.core.domain.materials

import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.model.MaterialKind
import dev.studyflow.core.testing.coroutines.testDispatcherProvider
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("MaterialImporter")
class MaterialImporterTest {
    @TempDir
    lateinit var stagingDir: File

    private val repository = FakeMaterialRepository()
    private val clock = Clock { TEST_WALL_CLOCK }

    @Test
    fun `streams a file into private storage and catalogues it by its true content`() =
        runTest {
            val bytes = pngBytes()
            val reader =
                fakeReader("content://media/1" to Fixture(bytes, displayName = "photo.png", mimeType = "image/png"))

            val outcome = importer(reader).import("content://media/1")

            val imported = outcome as? ImportOutcome.Imported ?: error("expected Imported but was $outcome")
            assertEquals("photo.png", imported.material.displayName)
            assertEquals("image/png", imported.material.mimeType)
            assertEquals(bytes.size.toLong(), imported.material.sizeBytes)
            assertEquals(sha256Hex(bytes), imported.material.contentHash.hex)
            assertEquals(imported.material, repository.findByContentHash(imported.material.contentHash))

            val stagedFile = File(URI(imported.material.localUri))
            assertTrue(stagedFile.exists())
            assertTrue(bytes.contentEquals(stagedFile.readBytes()))
        }

    @Test
    fun `a misleading resolver MIME is corrected by sniffing the actual bytes`() =
        runTest {
            val bytes = pngBytes()
            // A generic resolver answer is exactly the case sniffing exists to see through.
            val reader =
                fakeReader(
                    "content://media/2" to
                        Fixture(bytes, displayName = "vacation", mimeType = "application/octet-stream"),
                )

            val outcome =
                importer(reader).import("content://media/2") as? ImportOutcome.Imported
                    ?: error("expected an import")

            assertEquals("image/png", outcome.material.mimeType)
        }

    @Test
    fun `a specific declared MIME is trusted over a coarser sniffed one`() =
        runTest {
            // A ZIP-family signature under a specific, matching top-level declared type (an office
            // document is itself a ZIP container) must not be downgraded to a generic archive.
            val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "rest of a docx".toByteArray()
            val reader =
                fakeReader(
                    "content://media/3" to
                        Fixture(
                            bytes,
                            displayName = "essay.docx",
                            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        ),
                )

            val outcome =
                importer(reader).import("content://media/3") as? ImportOutcome.Imported
                    ?: error("expected an import")

            assertEquals(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                outcome.material.mimeType,
            )
        }

    @Test
    fun `a file whose content hash already exists is linked, not re-copied`() =
        runTest {
            val bytes = "identical bytes".toByteArray()
            val reader =
                fakeReader(
                    "content://media/first" to Fixture(bytes, displayName = "first.txt", mimeType = "text/plain"),
                    "content://media/second" to Fixture(bytes, displayName = "second.txt", mimeType = "text/plain"),
                )
            val subject = importer(reader)

            val first =
                subject.import("content://media/first") as? ImportOutcome.Imported ?: error("expected an import")
            val second =
                subject.import("content://media/second") as? ImportOutcome.DuplicateFound
                    ?: error("expected a duplicate")

            assertEquals(first.material, second.existing)
            assertEquals(1, repository.observeAll().first().size)
            // Only the first import's file remains; the duplicate's transient copy was cleaned up.
            assertEquals(1, stagingDir.listFiles()?.size)
        }

    @Test
    fun `a file over its kind's size ceiling is rejected without exhausting memory or disk`() =
        runTest {
            val limits =
                ImportLimits.DEFAULT.copy(
                    maxBytesByKind = mapOf(MaterialKind.OTHER to TINY_LIMIT_BYTES),
                    defaultMaxBytes = TINY_LIMIT_BYTES,
                )
            val bytes = ByteArray(1024) { 'a'.code.toByte() }
            val reader =
                fakeReader(
                    "content://media/big" to
                        Fixture(bytes, displayName = "big.bin", mimeType = null, declaredSize = null),
                )

            val outcome =
                importer(reader, limits).import("content://media/big") as? ImportOutcome.Rejected
                    ?: error("expected a rejection")

            assertEquals(ImportRejectionReason.FILE_TOO_LARGE, outcome.reason)
            assertEquals(0, stagingDir.listFiles()?.size, "the partial copy must not be left behind")
            assertNull(repository.observeAll().first().firstOrNull())
        }

    @Test
    fun `a declared size already over budget is rejected before the stream is even opened`() =
        runTest {
            val overBudget = ImportLimits.DEFAULT.maxBytesFor(MaterialKind.VIDEO) + 1
            val reader =
                object : ImportContentReader {
                    override fun queryMetadata(uri: String) = ImportContentMetadata("huge.mp4", overBudget, "video/mp4")

                    override fun openInputStream(uri: String): InputStream =
                        error("must not be opened once the declared size already exceeds the limit")

                    override fun takePersistableReadPermission(uri: String) = Unit
                }

            val outcome =
                importer(reader).import("content://media/huge") as? ImportOutcome.Rejected
                    ?: error("expected a rejection")

            assertEquals(ImportRejectionReason.FILE_TOO_LARGE, outcome.reason)
        }

    @Test
    fun `an installable package is blocked regardless of its declared size`() =
        runTest {
            val bytes = "not actually malware, but blocked anyway".toByteArray()
            val reader =
                fakeReader(
                    "content://media/apk" to
                        Fixture(bytes, displayName = "definitely-fine.apk", mimeType = "application/octet-stream"),
                )

            val outcome =
                importer(reader).import("content://media/apk") as? ImportOutcome.Rejected
                    ?: error("expected a rejection")

            assertEquals(ImportRejectionReason.BLOCKED_TYPE, outcome.reason)
            assertEquals(0, stagingDir.listFiles()?.size)
        }

    @Test
    fun `an unreadable URI fails cleanly instead of throwing`() =
        runTest {
            val reader =
                object : ImportContentReader {
                    override fun queryMetadata(uri: String) = ImportContentMetadata("notes.pdf", 10L, "application/pdf")

                    override fun openInputStream(uri: String): InputStream = throw FileNotFoundException(uri)

                    override fun takePersistableReadPermission(uri: String) = Unit
                }

            val outcome =
                importer(reader).import("content://gone") as? ImportOutcome.Failed ?: error("expected a failure")

            assertEquals(ImportFailureReason.UNREADABLE, outcome.reason)
            assertEquals(0, stagingDir.listFiles()?.size)
        }

    @Test
    fun `a missing display name and an unhelpful URI still produce something to show`() =
        runTest {
            val bytes = "content with no name at all".toByteArray()
            val reader =
                fakeReader("content://authority/" to Fixture(bytes, displayName = null, mimeType = "text/plain"))

            val outcome =
                importer(reader).import("content://authority/") as? ImportOutcome.Imported
                    ?: error("expected an import")

            assertTrue(outcome.material.displayName.isNotBlank())
            assertTrue(outcome.material.displayName.startsWith("imported-file-"))
        }

    @Test
    fun `cancellation mid-copy leaves no partial file behind`() =
        runTest {
            lateinit var job: Job
            val reader =
                object : ImportContentReader {
                    override fun queryMetadata(uri: String) = ImportContentMetadata("big.bin", null, null)

                    override fun openInputStream(uri: String): InputStream =
                        object : InputStream() {
                            var calls = 0

                            override fun read(): Int = error("unused")

                            override fun read(
                                b: ByteArray,
                                off: Int,
                                len: Int,
                            ): Int {
                                calls += 1
                                return if (calls == 1) {
                                    // Bytes were already read; cancellation is only observed on the
                                    // *next* loop iteration, exactly as real cancellation checks work.
                                    job.cancel()
                                    10
                                } else {
                                    -1
                                }
                            }

                            override fun close() = Unit
                        }

                    override fun takePersistableReadPermission(uri: String) = Unit
                }

            val subject = importer(reader)
            job = launch { runCatching { subject.import("content://slow") } }
            advanceUntilIdle()

            assertEquals(0, stagingDir.listFiles()?.size, "a cancelled import must not leave a partial file")
        }

    private fun TestScope.importer(
        reader: ImportContentReader,
        limits: ImportLimits = ImportLimits.DEFAULT,
    ): MaterialImporter =
        MaterialImporter(
            contentReader = reader,
            repository = repository,
            destinationDirectory = { stagingDir },
            clock = clock,
            dispatcherProvider = testDispatcherProvider(),
            limits = limits,
        )

    private data class Fixture(
        val bytes: ByteArray,
        val displayName: String?,
        val mimeType: String?,
        val declaredSize: Long? = bytes.size.toLong(),
    )

    private fun fakeReader(vararg fixtures: Pair<String, Fixture>): ImportContentReader {
        val byUri = fixtures.toMap()
        return object : ImportContentReader {
            override fun queryMetadata(uri: String): ImportContentMetadata {
                val fixture = byUri.getValue(uri)
                return ImportContentMetadata(fixture.displayName, fixture.declaredSize, fixture.mimeType)
            }

            override fun openInputStream(uri: String): InputStream = ByteArrayInputStream(byUri.getValue(uri).bytes)

            override fun takePersistableReadPermission(uri: String) = Unit
        }
    }

    private fun pngBytes(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64) { it.toByte() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }

    private companion object {
        const val TINY_LIMIT_BYTES = 16L
    }
}
