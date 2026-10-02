package dev.studyflow.feature.materials

import dev.studyflow.core.domain.materials.ImportRejectionReason
import dev.studyflow.core.domain.materials.UploadWaitReason
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.network.error.UserFacingMessage
import dev.studyflow.core.testing.data.testMaterial
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MaterialsCopy")
class MaterialsCopyTest {
    @Test
    fun `an unreachable backend reads as a permanent service problem rather than pending or offline`() {
        val failed =
            testMaterial(
                sync = SyncState.Failed(SyncState.Failed.BACKEND_UNREACHABLE, retryable = false),
                localUri = "file:///a",
            )
        val text = MaterialsCopy.uploadStatus(failed, UploadWaitReason.WAITING_FOR_WIFI)

        assertEquals(UserFacingMessage.BackendUnreachable.defaultText, text)
        assertFalse(Regex("\\d{3}|Exception|https?://|_").containsMatchIn(text.orEmpty()))
    }

    @Test
    fun `a pending upload held by Wi-Fi-only says so, distinct from every other upload state`() {
        val pending = testMaterial(sync = SyncState.Pending, localUri = "file:///a")
        val waiting = MaterialsCopy.uploadStatus(pending, UploadWaitReason.WAITING_FOR_WIFI)

        assertEquals("Waiting for Wi-Fi", waiting)
        val others =
            setOf(
                MaterialsCopy.uploadStatus(pending, waitReason = null),
                MaterialsCopy.uploadStatus(pending.copy(sync = SyncState.Uploading(1, 2)), null),
                MaterialsCopy.uploadStatus(pending.copy(sync = SyncState.Failed("x", retryable = true)), null),
            )
        assertEquals(setOf("Pending upload", "Uploading", "Upload failed"), others)
        assertNull(MaterialsCopy.uploadStatus(pending.copy(sync = SyncState.Synced), null))
    }

    @Test
    fun `a material whose file is gone reads as missing, not as a failure worth retrying`() {
        val missing = testMaterial(sync = SyncState.Failed("material has no local file", retryable = false))

        assertEquals("File missing", MaterialsCopy.uploadStatus(missing, null))
    }

    @Test
    fun `waiting and rejection copy is plain language with no codes or exception text`() {
        val copy =
            UploadWaitReason.entries.flatMap { listOf(MaterialsCopy.waiting(it), MaterialsCopy.waitingNotice(it)) } +
                ImportRejectionReason.entries.map(MaterialsCopy::rejection)

        copy.forEach { text ->
            assertFalse(Regex("\\d{3}|Exception|_").containsMatchIn(text), text)
        }
    }
}
