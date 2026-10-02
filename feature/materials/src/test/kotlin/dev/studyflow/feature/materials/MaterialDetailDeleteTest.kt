package dev.studyflow.feature.materials

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import dev.studyflow.core.common.coroutines.StandardDispatcherProvider
import dev.studyflow.core.designsystem.theme.StudyFlowTheme
import dev.studyflow.core.domain.materials.ArchiveReader
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.storage.PresignedUrl
import dev.studyflow.core.storage.SignedPart
import dev.studyflow.core.storage.StoredObject
import dev.studyflow.core.storage.UploadRequest
import dev.studyflow.core.storage.UploadSession
import dev.studyflow.core.storage.UploadedPart
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.data.testMaterial
import dev.studyflow.feature.materials.data.ArchiveEntryExtractor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration

/**
 * Deleting from the detail screen soft-deletes the row, so the repository emits `null` for the
 * material before `CloseMaterial` has been turned into navigation. The detail screen is still
 * composed in that window — here it stays composed, because the host never removes it — and its
 * outgoing content branch must keep rendering rather than crash on the missing material.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaterialDetailDeleteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val filesDir = TemporaryFolder()

    private val repository = FakeMaterialRepository()

    @Test
    fun `deleting while the detail screen is composed survives the repository emitting null`() {
        runBlocking { repository.save(testMaterial(id = MATERIAL_ID, displayName = "notes.pdf")) }
        val viewModel = viewModel()
        var closeRequests = 0
        composeRule.setContent {
            StudyFlowTheme(dynamicColor = false, edgeToEdge = false) {
                MaterialDetailRoute(
                    materialId = MATERIAL_ID,
                    onBack = { closeRequests++ },
                    viewModel = viewModel,
                )
            }
        }
        composeRule.onNodeWithText("Delete").assertExists()

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()

        assertNull(
            "the repository has already emitted null for the deleted material",
            runBlocking { repository.observeById(MATERIAL_ID).first() },
        )
        assertEquals("delete asks the host to close the screen exactly once", 1, closeRequests)
        composeRule.onNodeWithText(NOT_FOUND_MESSAGE).assertExists()
        // Mid cross-fade the outgoing branch still draws the material it was chosen with, not a
        // blank frame.
        composeRule.onNodeWithText("Delete").assertExists()

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText(NOT_FOUND_MESSAGE).assertExists()
        composeRule.onNodeWithText("Delete").assertDoesNotExist()
    }

    private fun viewModel(): MaterialDetailViewModel =
        MaterialDetailViewModel(
            SavedStateHandle(),
            repository,
            UnusedObjectStore,
            ArchiveEntryExtractor(
                reader = ArchiveReader(StandardDispatcherProvider),
                archivesDirectory = { filesDir.root },
            ),
        )

    /** The material under test has neither a local copy nor a remote key, so nothing is signed. */
    private object UnusedObjectStore : ObjectStore {
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

        override suspend fun getDownloadUrl(
            key: ObjectKey,
            ttl: Duration,
        ): PresignedUrl = error("not needed")

        override suspend fun delete(key: ObjectKey) {
            error("not needed")
        }

        override suspend fun stat(key: ObjectKey): StoredObject? = error("not needed")
    }

    private companion object {
        const val MATERIAL_ID = "material-1"
        const val NOT_FOUND_MESSAGE =
            "This material no longer exists. It may have been deleted on another device."
    }
}
