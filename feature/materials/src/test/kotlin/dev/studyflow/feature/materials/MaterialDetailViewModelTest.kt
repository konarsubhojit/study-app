package dev.studyflow.feature.materials

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.studyflow.core.domain.materials.ArchiveReader
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.storage.PresignedUrl
import dev.studyflow.core.storage.SignedPart
import dev.studyflow.core.storage.StoredObject
import dev.studyflow.core.storage.UploadRequest
import dev.studyflow.core.storage.UploadSession
import dev.studyflow.core.storage.UploadedPart
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import dev.studyflow.core.testing.coroutines.testDispatcherProvider
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.data.testMaterial
import dev.studyflow.feature.materials.data.ArchiveEntryExtractor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("MaterialDetailViewModel")
class MaterialDetailViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    @TempDir
    lateinit var filesDir: File

    private val repository = FakeMaterialRepository()
    private val objectStore = FakeObjectStore()

    @Test
    fun `loading a known material settles into its snapshot`() =
        runTest(mainDispatcher.dispatcher) {
            repository.save(testMaterial(id = "material-1", displayName = "notes.pdf"))
            val viewModel = viewModel()

            viewModel.onEvent(MaterialDetailUiEvent.Load("material-1"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(!state.loading)
            assertEquals("notes.pdf", state.material?.displayName)
        }

    @Test
    fun `loading an unknown material settles into not-found rather than staying loading forever`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(MaterialDetailUiEvent.Load("missing"))
            advanceUntilIdle()

            assertTrue(viewModel.state.value.notFound)
        }

    @Test
    fun `catalogue updates for the loaded material are reflected without a new Load event`() =
        runTest(mainDispatcher.dispatcher) {
            repository.save(testMaterial(id = "material-1", displayName = "draft.pdf"))
            val viewModel = viewModel()
            viewModel.onEvent(MaterialDetailUiEvent.Load("material-1"))
            advanceUntilIdle()

            repository.save(testMaterial(id = "material-1", displayName = "final.pdf"))
            advanceUntilIdle()

            assertEquals(
                "final.pdf",
                viewModel.state.value.material
                    ?.displayName,
            )
        }

    @Test
    fun `a cached local file is used as the preview source without signing a remote URL`() =
        runTest(mainDispatcher.dispatcher) {
            repository.save(testMaterial(id = "material-1", localUri = "/files/materials/notes.pdf"))
            val viewModel = viewModel()

            viewModel.onEvent(MaterialDetailUiEvent.Load("material-1"))
            advanceUntilIdle()

            assertEquals(MaterialPreviewSource.Local("/files/materials/notes.pdf"), viewModel.state.value.previewSource)
            assertTrue(objectStore.requestedKeys.isEmpty())
        }

    @Test
    fun `a remote-only material resolves a presigned preview source`() =
        runTest(mainDispatcher.dispatcher) {
            objectStore.urls[ObjectKey("materials/remote")] = "https://cdn.example.test/material"
            repository.save(testMaterial(id = "material-1").copy(remoteKey = "materials/remote", localPath = null))
            val viewModel = viewModel()

            viewModel.onEvent(MaterialDetailUiEvent.Load("material-1"))
            advanceUntilIdle()

            assertEquals(
                MaterialPreviewSource.Remote("https://cdn.example.test/material"),
                viewModel.state.value.previewSource,
            )
            assertEquals(listOf(ObjectKey("materials/remote")), objectStore.requestedKeys)
        }

    @Test
    fun `preview progress events are persisted to the repository`() =
        runTest(mainDispatcher.dispatcher) {
            repository.save(testMaterial(id = "material-1"))
            val viewModel = viewModel()
            viewModel.onEvent(MaterialDetailUiEvent.Load("material-1"))

            viewModel.onEvent(MaterialDetailUiEvent.PdfPageChanged(8))
            viewModel.onEvent(MaterialDetailUiEvent.PlaybackChanged(positionMillis = 120_000, playbackSpeed = 1.5f))
            advanceUntilIdle()

            val material = viewModel.state.value.material
            assertEquals(8, material?.previewPageIndex)
            assertEquals(120_000, material?.previewPositionMillis)
            assertEquals(1.5f, material?.playbackSpeed)
        }

    @Test
    fun `deleting the material emits a close effect`() =
        runTest(mainDispatcher.dispatcher) {
            repository.save(testMaterial(id = "material-1"))
            val viewModel = viewModel()
            viewModel.onEvent(MaterialDetailUiEvent.Load("material-1"))
            advanceUntilIdle()

            viewModel.effects.test {
                viewModel.onEvent(MaterialDetailUiEvent.DeleteMaterial)
                advanceUntilIdle()

                assertEquals(MaterialDetailUiEffect.CloseMaterial, awaitItem())
                assertTrue(viewModel.state.value.notFound)
            }
        }

    @Test
    fun `an archive material lists its safe entries`() =
        runTest(mainDispatcher.dispatcher) {
            val archive = storedZipFile("notes.zip", "a.txt" to "hello".toByteArray(), "b.txt" to "world".toByteArray())
            repository.save(testMaterial(id = "material-1", mimeType = "application/zip", localUri = archive.path))
            val viewModel = viewModel()

            viewModel.onEvent(MaterialDetailUiEvent.Load("material-1"))
            advanceUntilIdle()

            val archivePreview = viewModel.state.value.archivePreview
            assertEquals(setOf("a.txt", "b.txt"), archivePreview?.entries?.map { it.name }?.toSet())
            assertNull(archivePreview?.message, "a clean archive needs no rejection summary")
        }

    @Test
    fun `a zip-slip entry never appears in the safe list and is explained instead`() =
        runTest(mainDispatcher.dispatcher) {
            val archive = storedZipFile("evil.zip", "../../evil.txt" to "payload".toByteArray())
            repository.save(testMaterial(id = "material-1", mimeType = "application/zip", localUri = archive.path))
            val viewModel = viewModel()

            viewModel.onEvent(MaterialDetailUiEvent.Load("material-1"))
            advanceUntilIdle()

            val archivePreview = viewModel.state.value.archivePreview
            assertTrue(archivePreview?.entries.orEmpty().isEmpty(), "an unsafe entry must never be offered")
            assertTrue(
                archivePreview?.message.orEmpty().contains("unsafe path"),
                "the rejection reason should be explained, was: ${archivePreview?.message}",
            )
        }

    @Test
    fun `extracting an entry exposes its local file once done`() =
        runTest(mainDispatcher.dispatcher) {
            val archive = storedZipFile("notes.zip", "a.txt" to "hello world".toByteArray())
            repository.save(testMaterial(id = "material-1", mimeType = "application/zip", localUri = archive.path))
            val viewModel = viewModel()
            viewModel.onEvent(MaterialDetailUiEvent.Load("material-1"))
            advanceUntilIdle()
            val entryPath =
                requireNotNull(
                    viewModel.state.value.archivePreview
                        ?.entries
                        ?.single()
                        ?.path,
                )

            viewModel.onEvent(MaterialDetailUiEvent.ExtractArchiveEntry(entryPath))
            advanceUntilIdle()

            val extraction =
                viewModel.state.value.archivePreview
                    ?.entries
                    ?.single()
                    ?.extraction
            val done = extraction as? ArchiveExtractionUiState.Done ?: error("expected Done but was $extraction")
            assertEquals("hello world", File(done.localPath).readText())
        }

    @Test
    fun `cancelling an extraction before it runs settles back to idle rather than staying stuck`() =
        runTest(mainDispatcher.dispatcher) {
            val archive = storedZipFile("notes.zip", "a.txt" to "hello world".toByteArray())
            repository.save(testMaterial(id = "material-1", mimeType = "application/zip", localUri = archive.path))
            val viewModel = viewModel()
            viewModel.onEvent(MaterialDetailUiEvent.Load("material-1"))
            advanceUntilIdle()
            val entryPath =
                requireNotNull(
                    viewModel.state.value.archivePreview
                        ?.entries
                        ?.single()
                        ?.path,
                )

            viewModel.onEvent(MaterialDetailUiEvent.ExtractArchiveEntry(entryPath))
            viewModel.onEvent(MaterialDetailUiEvent.CancelArchiveExtraction(entryPath))
            advanceUntilIdle()

            assertEquals(
                ArchiveExtractionUiState.Idle,
                viewModel.state.value.archivePreview
                    ?.entries
                    ?.single()
                    ?.extraction,
            )
        }

    private fun TestScope.viewModel(): MaterialDetailViewModel =
        MaterialDetailViewModel(
            SavedStateHandle(),
            repository,
            objectStore,
            ArchiveEntryExtractor(
                reader = ArchiveReader(testDispatcherProvider()),
                archivesDirectory = { File(filesDir, "archives") },
            ),
        )

    private fun storedZipFile(
        fileName: String,
        vararg entries: Pair<String, ByteArray>,
    ): File {
        val file = File(filesDir, fileName)
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

    private class FakeObjectStore : ObjectStore {
        val urls = mutableMapOf<ObjectKey, String>()
        val requestedKeys = mutableListOf<ObjectKey>()

        override suspend fun getDownloadUrl(
            key: ObjectKey,
            ttl: kotlin.time.Duration,
        ): PresignedUrl {
            requestedKeys += key
            return PresignedUrl(
                url = urls.getValue(key),
                expiresAt = dev.studyflow.core.testing.data.TEST_WALL_CLOCK + ttl,
            )
        }

        override suspend fun initUpload(request: UploadRequest): UploadSession = error("not needed")

        override suspend fun uploadPart(
            session: UploadSession,
            part: SignedPart,
            bytes: ByteArray,
        ): UploadedPart = error("not needed")

        override suspend fun completeUpload(
            session: UploadSession,
            parts: List<UploadedPart>,
        ): StoredObject = error("not needed")

        override suspend fun delete(key: ObjectKey) {
            error("not needed")
        }

        override suspend fun stat(key: ObjectKey): StoredObject? = error("not needed")
    }
}
