package dev.studyflow.core.storage.local

import dev.studyflow.core.domain.materials.MultipartLimits
import dev.studyflow.core.domain.materials.UploadPart
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStore
import dev.studyflow.core.storage.ObjectStoreException
import dev.studyflow.core.storage.SignedPart
import dev.studyflow.core.storage.UploadRequest
import dev.studyflow.core.storage.UploadSession
import dev.studyflow.core.storage.UploadedPart
import dev.studyflow.core.storage.assertFailsWith
import dev.studyflow.core.storage.local.InMemoryObjectStore.Companion.sha256Hex
import dev.studyflow.core.testing.time.FakeDevice
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@DisplayName("InMemoryObjectStore")
class InMemoryObjectStoreTest {
    private val device = FakeDevice()
    private val store = InMemoryObjectStore(clock = device, limits = TINY_PARTS)

    @Test
    fun `a chunked upload arrives byte-for-byte`() =
        runTest {
            val bytes = "a lecture recording, in miniature".encodeToByteArray()

            val stored = store.upload(bytes)

            assertEquals(bytes.size.toLong(), stored.sizeBytes)
            assertEquals(ContentHash(sha256Hex(bytes)), stored.contentHash)
            assertArrayEquals(bytes, store.bytesOf(stored.key))
            assertEquals(stored, store.stat(stored.key))
        }

    @Test
    fun `an empty file is an object, not a special case`() =
        runTest {
            val stored = store.upload(ByteArray(0))

            assertEquals(0L, stored.sizeBytes)
            assertArrayEquals(ByteArray(0), store.bytesOf(stored.key))
        }

    @Test
    fun `a file larger than one part is split and reassembled in order`() =
        runTest {
            val bytes = ByteArray(PART_SIZE.toInt() * 3 + 1) { index -> index.toByte() }

            val session = store.initUpload(requestFor(bytes))

            assertEquals(4, session.parts.size)
            val stored = store.completeUpload(session, store.sendAllParts(session, bytes))
            assertArrayEquals(bytes, store.bytesOf(stored.key))
        }

    @Test
    fun `an object whose bytes do not match the digest the device computed is refused`() =
        runTest {
            val declared = requestFor("good".encodeToByteArray())
            val session = store.initUpload(declared)
            val acknowledged = store.sendAllParts(session, "evil".encodeToByteArray())

            val failure =
                assertFailsWith<ObjectStoreException.Integrity> { store.completeUpload(session, acknowledged) }

            assertEquals(false, failure.retryable)
            assertNull(store.stat(declared.key))
        }

    @Test
    fun `completing an upload twice fails instead of silently rewriting the object`() =
        runTest {
            val bytes = "once".encodeToByteArray()
            val session = store.initUpload(requestFor(bytes))
            val acknowledged = store.sendAllParts(session, bytes)
            store.completeUpload(session, acknowledged)

            assertFailsWith<ObjectStoreException.AccessDenied> { store.completeUpload(session, acknowledged) }
        }

    @Test
    fun `a part sent after the session expired is refused, as a presigned URL would be`() =
        runTest {
            val bytes = "late".encodeToByteArray()
            val session = store.initUpload(requestFor(bytes))

            device.advance(1.hours)

            val failure =
                assertFailsWith<ObjectStoreException.AccessDenied> {
                    store.uploadPart(session, session.parts.single(), bytes)
                }
            assertEquals(false, failure.retryable)
        }

    @Test
    fun `completing an upload with a part missing is an integrity failure, not a truncated object`() =
        runTest {
            val bytes = ByteArray(PART_SIZE.toInt() * 2) { 1 }
            val session = store.initUpload(requestFor(bytes))
            val sent = store.uploadPart(session, session.parts.first(), bytes.copyOfRange(0, PART_SIZE.toInt()))

            assertFailsWith<ObjectStoreException.Integrity> { store.completeUpload(session, listOf(sent)) }
        }

    @Test
    fun `a part the store never signed is refused rather than stored and ignored`() =
        runTest {
            val bytes = "stray".encodeToByteArray()
            val session = store.initUpload(requestFor(bytes))
            val stray =
                SignedPart(
                    part = UploadPart(number = 9, offset = 0, size = bytes.size.toLong()),
                    url = session.parts.first().url,
                )

            assertFailsWith<ObjectStoreException.AccessDenied> { store.uploadPart(session, stray, bytes) }
        }

    @Test
    fun `an object too large for process memory is refused up front, not with an OutOfMemoryError`() =
        runTest {
            val huge =
                UploadRequest.ofMaterial(
                    contentHash = ContentHash(sha256Hex(ByteArray(0))),
                    sizeBytes = Int.MAX_VALUE.toLong() + 1,
                    contentType = CONTENT_TYPE,
                )

            assertFailsWith<ObjectStoreException.QuotaExceeded> { store.initUpload(huge) }
        }

    @Test
    fun `a download URL expires, and never outlives what the store is willing to sign`() =
        runTest {
            val stored = store.upload("readable".encodeToByteArray())

            val short = store.getDownloadUrl(stored.key, ttl = 1.minutes)
            val greedy = store.getDownloadUrl(stored.key, ttl = 24.hours)

            assertEquals(device.now() + 1.minutes, short.expiresAt)
            assertEquals(device.now() + InMemoryObjectStore.DEFAULT_URL_TTL, greedy.expiresAt)
            assertTrue(short.url.startsWith("studyflow-local://"), short.url)
        }

    @Test
    fun `a download URL requires a positive requested lifetime`() =
        runTest {
            val stored = store.upload("readable".encodeToByteArray())

            assertFailsWith<IllegalArgumentException> { store.getDownloadUrl(stored.key, ttl = ZERO) }
        }

    @Test
    fun `a local store rejects URL lifetimes that exceed the security limit`() {
        assertFailsWith<IllegalArgumentException> {
            InMemoryObjectStore(urlTtl = ObjectStore.MAX_PRESIGNED_URL_TTL + 1.minutes)
        }
    }

    @Test
    fun `there is no URL for an object nobody uploaded`() =
        runTest {
            val absent = ObjectKey("materials/${"f".repeat(64)}")

            val failure = assertFailsWith<ObjectStoreException.NotFound> { store.getDownloadUrl(absent) }

            assertEquals(absent, failure.key)
        }

    @Test
    fun `deleting is idempotent, so a replayed delete does not strand the queue`() =
        runTest {
            val stored = store.upload("gone soon".encodeToByteArray())

            store.delete(stored.key)
            store.delete(stored.key)

            assertNull(store.stat(stored.key))
            assertNull(store.bytesOf(stored.key))
        }

    private suspend fun InMemoryObjectStore.upload(bytes: ByteArray) =
        initUpload(requestFor(bytes)).let { session -> completeUpload(session, sendAllParts(session, bytes)) }

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

    private fun requestFor(bytes: ByteArray): UploadRequest =
        UploadRequest.ofMaterial(
            contentHash = ContentHash(sha256Hex(bytes)),
            sizeBytes = bytes.size.toLong(),
            contentType = CONTENT_TYPE,
        )

    private companion object {
        const val PART_SIZE = 8L
        const val CONTENT_TYPE = "application/pdf"

        /** Real limits with the numbers shrunk, so multipart behaviour is exercised in a few bytes. */
        val TINY_PARTS =
            MultipartLimits(minPartSizeBytes = PART_SIZE, maxPartSizeBytes = PART_SIZE, maxParts = 10_000)
    }
}
