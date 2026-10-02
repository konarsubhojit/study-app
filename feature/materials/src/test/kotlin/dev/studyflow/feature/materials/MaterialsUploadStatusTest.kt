package dev.studyflow.feature.materials

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.studyflow.core.designsystem.theme.StudyFlowTheme
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.network.error.UserFacingMessage
import dev.studyflow.core.testing.data.testMaterial
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaterialsUploadStatusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `an unreachable backend explains the failure without offering a futile retry`() {
        show(SyncState.Failed(SyncState.Failed.BACKEND_UNREACHABLE, retryable = false))

        composeRule.onNodeWithText(UserFacingMessage.BackendUnreachable.defaultText).assertExists()
        composeRule.onNodeWithText("Pending upload").assertDoesNotExist()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `an ordinary upload failure still offers a working retry action`() {
        val events = mutableListOf<MaterialsUiEvent>()
        show(SyncState.Failed("connection reset", retryable = true)) { events += it }

        composeRule.onNodeWithText("Upload failed").assertExists()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(listOf(MaterialsUiEvent.RetryUpload("material-1")), events)
    }

    private fun show(
        sync: SyncState,
        onEvent: (MaterialsUiEvent) -> Unit = {},
    ) {
        composeRule.setContent {
            StudyFlowTheme(dynamicColor = false, edgeToEdge = false) {
                MaterialsScreen(
                    state =
                        MaterialsUiState(
                            loaded = true,
                            catalog =
                                listOf(
                                    testMaterial(id = "material-1", sync = sync, localUri = "file:///notes.pdf"),
                                ),
                        ),
                    onEvent = onEvent,
                    onPickPhotosAndVideos = {},
                    onPickDocuments = {},
                )
            }
        }
    }
}
