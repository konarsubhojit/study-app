package dev.studyflow.feature.materials

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.domain.materials.ImportContentMetadata
import dev.studyflow.core.domain.materials.ImportContentReader
import dev.studyflow.core.domain.materials.MaterialImporter
import dev.studyflow.core.domain.materials.ShareImportInbox
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import dev.studyflow.core.testing.coroutines.TestDispatcherProvider
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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
