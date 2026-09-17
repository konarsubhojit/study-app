package dev.studyflow.feature.materials

import androidx.lifecycle.SavedStateHandle
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.data.testMaterial
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

    private fun viewModel(): MaterialDetailViewModel = MaterialDetailViewModel(SavedStateHandle(), repository)
}
