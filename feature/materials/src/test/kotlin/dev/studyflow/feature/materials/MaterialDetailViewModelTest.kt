package dev.studyflow.feature.materials

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.data.testMaterial
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.storage.PresignedUrl
import dev.studyflow.core.storage.StoredObject
import dev.studyflow.core.storage.UploadRequest
import dev.studyflow.core.storage.UploadSession
import dev.studyflow.core.storage.SignedPart
import dev.studyflow.core.storage.UploadedPart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("MaterialDetailViewModel")
class MaterialDetailViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

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

            assertEquals(MaterialPreviewSource.Remote("https://cdn.example.test/material"), viewModel.state.value.previewSource)
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

    private fun viewModel(): MaterialDetailViewModel = MaterialDetailViewModel(SavedStateHandle(), repository, objectStore)

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
