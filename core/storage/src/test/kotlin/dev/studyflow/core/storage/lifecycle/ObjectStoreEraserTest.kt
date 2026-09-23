package dev.studyflow.core.storage.lifecycle

import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.UploadRequest
import dev.studyflow.core.storage.UploadSession
import dev.studyflow.core.storage.UploadedPart
import dev.studyflow.core.storage.local.InMemoryObjectStore
import dev.studyflow.core.storage.local.InMemoryObjectStore.Companion.sha256Hex
import dev.studyflow.core.testing.time.FakeDevice
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Object store erasure")
class ObjectStoreEraserTest {
    private val store = InMemoryObjectStore(clock = FakeDevice())

    @Test
    fun `every key the device knows about is removed`() =
        runTest {
            val notes = store.upload("lecture notes".encodeToByteArray())
            val slides = store.upload("slides".encodeToByteArray())

            eraser(listOf(notes, slides)).erase()

            assertNull(store.stat(notes))
            assertNull(store.stat(slides))
        }

    @Test
    fun `a key the server already removed does not fail the erasure`() =
        runTest {
            val notes = store.upload("lecture notes".encodeToByteArray())
            val neverUploaded = ObjectKey.ofMaterial(ContentHash(sha256Hex("never uploaded".encodeToByteArray())))

            eraser(listOf(neverUploaded, notes)).erase()

            assertNull(store.stat(notes), "a missing key must not strand the keys queued behind it")
        }

    private fun eraser(keys: List<ObjectKey>) = ObjectStoreEraser(store) { keys }

    private suspend fun InMemoryObjectStore.upload(bytes: ByteArray): ObjectKey {
        val request =
            UploadRequest.ofMaterial(
                contentHash = ContentHash(sha256Hex(bytes)),
                sizeBytes = bytes.size.toLong(),
                contentType = "application/pdf",
            )
        val session = initUpload(request)
        return completeUpload(session, sendAllParts(session, bytes)).key
    }

    private suspend fun InMemoryObjectStore.sendAllParts(
        session: UploadSession,
        bytes: ByteArray,
    ): List<UploadedPart> =
        session.parts.map { part ->
            uploadPart(
                session = session,
                part = part,
                bytes = bytes.copyOfRange(part.offset.toInt(), (part.offset + part.size).toInt()),
            )
        }
}
