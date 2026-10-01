package dev.studyflow.feature.materials

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.domain.materials.ImportContentMetadata
import dev.studyflow.core.domain.materials.ImportContentReader
import dev.studyflow.core.domain.materials.MaterialImporter
import dev.studyflow.core.domain.materials.ShareImportInbox
import dev.studyflow.core.domain.materials.UploadWaitReason
import dev.studyflow.core.domain.materials.thumbnails.ThumbnailLoader
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import dev.studyflow.core.testing.coroutines.TestDispatcherProvider
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.data.FakeMaterialUploadCoordinator
import dev.studyflow.core.testing.data.FakeThumbnailCache
import dev.studyflow.core.testing.data.FakeThumbnailRenderer
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK
import dev.studyflow.core.testing.data.testMaterial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("MaterialsViewModel")
class MaterialsViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    @TempDir
    lateinit var stagingDir: File

    private val repository = FakeMaterialRepository()
    private val shareImportInbox = ShareImportInbox()
    private val uploadCoordinator = FakeMaterialUploadCoordinator()

    @Test
    fun `importing a picked file adds it to the catalogue and reports a result`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel(fakeReader("content://one" to "hello".toByteArray()))

            viewModel.onEvent(MaterialsUiEvent.ImportUris(listOf("content://one")))
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(1, state.catalog.size)
                assertEquals(1, state.results.size)
                assertTrue(state.results.single().status is MaterialImportResultStatus.Imported)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(
                listOf(
                    viewModel.state.value.catalog
                        .single()
                        .id,
                ),
                uploadCoordinator.enqueued,
            )
        }

    @Test
    fun `dismissing results clears the banner without touching the catalogue`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel(fakeReader("content://one" to "hello".toByteArray()))
            viewModel.onEvent(MaterialsUiEvent.ImportUris(listOf("content://one")))
            advanceUntilIdle()

            viewModel.onEvent(MaterialsUiEvent.DismissResults)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.results.isEmpty())
                assertEquals(1, state.catalog.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a duplicate import offers a link to the existing entry instead of a second one`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel =
                viewModel(
                    fakeReader(
                        "content://a" to "same bytes".toByteArray(),
                        "content://b" to "same bytes".toByteArray(),
                    ),
                )

            viewModel.onEvent(MaterialsUiEvent.ImportUris(listOf("content://a", "content://b")))
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(1, state.catalog.size)
                val duplicate = state.results.last().status as MaterialImportResultStatus.Duplicate
                assertEquals(state.catalog.single().id, duplicate.existingId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `viewing an existing duplicate navigates to its detail destination`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel =
                viewModel(
                    fakeReader(
                        "content://a" to "same bytes".toByteArray(),
                        "content://b" to "same bytes".toByteArray(),
                    ),
                )
            viewModel.onEvent(MaterialsUiEvent.ImportUris(listOf("content://a", "content://b")))
            advanceUntilIdle()
            val existingId =
                viewModel.state.value.catalog
                    .single()
                    .id

            viewModel.effects.test {
                viewModel.onEvent(MaterialsUiEvent.ViewExisting(existingId))

                assertEquals(MaterialsUiEffect.NavigateToMaterial(existingId), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a share intent already queued when the screen appears is imported automatically`() =
        runTest(mainDispatcher.dispatcher) {
            shareImportInbox.offer(listOf("content://shared"))

            val viewModel = viewModel(fakeReader("content://shared" to "shared bytes".toByteArray()))
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(1, state.catalog.size)
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(shareImportInbox.pending.value.isEmpty())
        }

    @Test
    fun `retrying an upload delegates to the coordinator for that material's id`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel(fakeReader("content://one" to "hello".toByteArray()))
            viewModel.onEvent(MaterialsUiEvent.ImportUris(listOf("content://one")))
            advanceUntilIdle()
            val materialId =
                viewModel.state.value.catalog
                    .single()
                    .id

            viewModel.onEvent(MaterialsUiEvent.RetryUpload(materialId))
            advanceUntilIdle()

            assertEquals(listOf(materialId), uploadCoordinator.retried)
        }

    @Test
    fun `an upload held by Wi-Fi-only reads as waiting and offers the upload setting`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel(fakeReader())
            uploadCoordinator.waitReason.value = UploadWaitReason.WAITING_FOR_WIFI

            viewModel.state.test {
                var state = awaitItem()
                while (!state.loaded) state = awaitItem()
                assertEquals(UploadWaitReason.WAITING_FOR_WIFI, state.uploadWaitReason)
                cancelAndIgnoreRemainingEvents()
            }
            viewModel.effects.test {
                viewModel.onEvent(MaterialsUiEvent.OpenUploadSettings)
                assertEquals(MaterialsUiEffect.NavigateToUploadSettings, awaitItem())
            }
        }

    @Test
    fun `re-attaching the original file restores a material whose file was lost and re-queues it`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel(fakeReader("content://one" to "hello".toByteArray()))
            val materialId = importAndLoseFile(viewModel)
            viewModel.effects.test {
                viewModel.onEvent(MaterialsUiEvent.ChooseReattachFile(materialId))
                assertEquals(MaterialsUiEffect.PickReattachFile(materialId), awaitItem())
            }

            viewModel.onEvent(MaterialsUiEvent.ReattachFile(materialId, "content://one"))
            advanceUntilIdle()

            val restored = repository.observeById(materialId).first()!!
            assertEquals(SyncState.Pending, restored.sync)
            assertTrue(restored.localPath != null)
            assertEquals(listOf(materialId, materialId), uploadCoordinator.enqueued)
        }

    @Test
    fun `re-attaching a different file is refused in plain words and changes nothing`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel =
                viewModel(
                    fakeReader("content://one" to "hello".toByteArray(), "content://two" to "other".toByteArray()),
                )
            val materialId = importAndLoseFile(viewModel)

            viewModel.onEvent(MaterialsUiEvent.ReattachFile(materialId, "content://two"))
            advanceUntilIdle()

            assertTrue(repository.observeById(materialId).first()!!.isMissingSource)
            assertEquals(
                MaterialImportResultStatus.Rejected(MaterialsCopy.REATTACH_MISMATCH),
                viewModel.state.value.results
                    .last()
                    .status,
            )
        }

    @Test
    fun `removing a material whose file was lost deletes it and cancels its upload`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel(fakeReader("content://one" to "hello".toByteArray()))
            val materialId = importAndLoseFile(viewModel)

            viewModel.onEvent(MaterialsUiEvent.RemoveMaterial(materialId))
            advanceUntilIdle()

            assertNull(repository.observeById(materialId).first(), "a removed material leaves the catalogue")
            assertEquals(listOf(materialId), uploadCoordinator.cancelled)
        }

    @Test
    fun `a material the device cannot render has no thumbnail and no download`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel(fakeReader())

            assertNull(
                viewModel.loadThumbnail(testMaterial(localUri = null)),
                "browsing a cloud-only material must not pull the original down to draw a cell",
            )
        }

    /** Imports `content://one`, then clears its local copy the way wiping app data would. */
    private suspend fun TestScope.importAndLoseFile(viewModel: MaterialsViewModel): String {
        viewModel.onEvent(MaterialsUiEvent.ImportUris(listOf("content://one")))
        advanceUntilIdle()
        val material = repository.observeAll().first().single()
        repository.save(
            material.copy(localPath = null, sync = SyncState.Failed("material has no local file", retryable = false)),
        )
        return material.id
    }

    private fun viewModel(reader: ImportContentReader): MaterialsViewModel {
        val importer =
            MaterialImporter(
                contentReader = reader,
                repository = repository,
                destinationDirectory = { stagingDir },
                clock = Clock { TEST_WALL_CLOCK },
                dispatcherProvider = TestDispatcherProvider(mainDispatcher.dispatcher),
            )
        return MaterialsViewModel(
            savedStateHandle = SavedStateHandle(),
            repository = repository,
            importer = importer,
            shareImportInbox = shareImportInbox,
            uploadCoordinator = uploadCoordinator,
            thumbnailLoader =
                ThumbnailLoader(
                    cache = FakeThumbnailCache(),
                    renderer = FakeThumbnailRenderer(),
                    dispatcherProvider = TestDispatcherProvider(mainDispatcher.dispatcher),
                ),
            dispatcherProvider = TestDispatcherProvider(mainDispatcher.dispatcher),
        )
    }

    private fun fakeReader(vararg files: Pair<String, ByteArray>): ImportContentReader {
        val byUri = files.toMap()
        return object : ImportContentReader {
            override fun queryMetadata(uri: String): ImportContentMetadata {
                val bytes = byUri.getValue(uri)
                return ImportContentMetadata(uri.substringAfterLast('/'), bytes.size.toLong(), "text/plain")
            }

            override fun openInputStream(uri: String): InputStream = ByteArrayInputStream(byUri.getValue(uri))

            override fun takePersistableReadPermission(uri: String) = Unit
        }
    }
}
