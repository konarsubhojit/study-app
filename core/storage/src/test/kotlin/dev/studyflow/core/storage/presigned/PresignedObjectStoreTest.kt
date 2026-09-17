package dev.studyflow.core.storage.presigned

import dev.studyflow.core.domain.materials.UploadPlanner
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStoreException
import dev.studyflow.core.storage.PresignedUrl
import dev.studyflow.core.storage.StoredObject
import dev.studyflow.core.storage.UploadRequest
import dev.studyflow.core.storage.UploadSession
import dev.studyflow.core.storage.UploadedPart
import dev.studyflow.core.storage.assertFailsWith
import dev.studyflow.core.storage.local.InMemoryObjectStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@DisplayName("PresignedObjectStore")
class PresignedObjectStoreTest {
    @Test
    fun `part bytes go straight to the signed URL, never through our API`() =
        runTest {
            var seen: HttpRequestData? = null
            val store =
                store { request ->
                    seen = request
                    respond(content = "", status = HttpStatusCode.OK, headers = etag("\"part-etag\""))
                }

            val session = store.initUpload(REQUEST)
            val uploaded = store.uploadPart(session, session.parts.single(), PAYLOAD)

            assertEquals(HttpMethod.Put, seen?.method)
            assertEquals(PART_URL, seen?.url.toString())
            assertEquals(UploadedPart(number = 1, etag = "part-etag", size = PAYLOAD.size.toLong()), uploaded)
        }

    @Test
    fun `a store that does not return an entity tag is still usable`() =
        runTest {
            val store = store { respond(content = "", status = HttpStatusCode.OK) }
            val session = store.initUpload(REQUEST)

            assertEquals("", store.uploadPart(session, session.parts.single(), PAYLOAD).etag)
        }

    @Test
    fun `an expired signature is not worth retrying with the same URL`() =
        runTest {
            val store = store { respondError(HttpStatusCode.Forbidden) }
            val session = store.initUpload(REQUEST)

            val failure =
                assertFailsWith<ObjectStoreException.AccessDenied> {
                    store.uploadPart(session, session.parts.single(), PAYLOAD)
                }

            assertFalse(failure.retryable)
        }

    @Test
    fun `a full bucket is reported as a quota failure rather than a flaky network`() =
        runTest {
            val store = store { respondError(HttpStatusCode.InsufficientStorage) }
            val session = store.initUpload(REQUEST)

            assertFailsWith<ObjectStoreException.QuotaExceeded> {
                store.uploadPart(session, session.parts.single(), PAYLOAD)
            }
        }

    @Test
    fun `a server error and a dropped connection are both worth retrying`() =
        runTest {
            val serverError = store { respondError(HttpStatusCode.ServiceUnavailable) }
            val dropped = store { throw IOException("connection reset") }

            listOf(serverError, dropped).forEach { store ->
                val session = store.initUpload(REQUEST)
                val failure =
                    assertFailsWith<ObjectStoreException.Transient> {
                        store.uploadPart(session, session.parts.single(), PAYLOAD)
                    }
                assertTrue(failure.retryable)
            }
        }

    @Test
    fun `a failure message names the part, never the signed URL it would leak`() =
        runTest {
            val store = store { respondError(HttpStatusCode.Forbidden) }
            val session = store.initUpload(REQUEST)

            val failure =
                assertFailsWith<ObjectStoreException> {
                    store.uploadPart(session, session.parts.single(), PAYLOAD)
                }

            assertFalse(failure.message.orEmpty().contains(SIGNATURE), failure.message.orEmpty())
        }

    @Test
    fun `sending the wrong number of bytes is a programming error, caught before the request`() =
        runTest {
            val store = store { respond(content = "", status = HttpStatusCode.OK) }
            val session = store.initUpload(REQUEST)

            assertFailsWith<IllegalArgumentException> {
                store.uploadPart(session, session.parts.single(), ByteArray(1))
            }
        }

    @Test
    fun `signing, completion, deletion and stat stay with the BFF`() =
        runTest {
            val source = FakeUrlSource()
            val store = store(source) { respond("", HttpStatusCode.OK) }

            val session = store.initUpload(REQUEST)
            store.completeUpload(session, listOf(UploadedPart(1, "etag", PAYLOAD.size.toLong())))
            store.getDownloadUrl(REQUEST.key, ttl = 1.minutes)
            store.delete(REQUEST.key)

            assertEquals(listOf("createUpload", "finishUpload", "downloadUrl", "delete"), source.calls)
            assertNull(store.stat(ObjectKey("materials/absent")))
        }

    private fun store(
        source: FakeUrlSource = FakeUrlSource(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): PresignedObjectStore = PresignedObjectStore(HttpClient(MockEngine(handler)), source)

    private fun etag(value: String): Headers = Headers.build { append(HttpHeaders.ETag, value) }

    /** Stands in for the BFF (#7), which is the only thing that ever holds a provider credential. */
    private class FakeUrlSource : PresignedUrlSource {
        val calls = mutableListOf<String>()

        override suspend fun createUpload(request: UploadRequest): UploadSession {
            calls += "createUpload"
            return UploadSession.of(
                key = request.key,
                uploadId = "upload-1",
                plan = UploadPlanner.plan(request.sizeBytes, request.contentHash),
                expiresAt = EXPIRY,
            ) { PresignedUrl(PART_URL, EXPIRY) }
        }

        override suspend fun finishUpload(
            session: UploadSession,
            parts: List<UploadedPart>,
        ): StoredObject {
            calls += "finishUpload"
            return StoredObject(
                key = session.key,
                sizeBytes = parts.sumOf { it.size },
                contentType = CONTENT_TYPE,
                contentHash = PAYLOAD_HASH,
                updatedAt = EXPIRY,
            )
        }

        override suspend fun downloadUrl(
            key: ObjectKey,
            ttl: Duration,
        ): PresignedUrl {
            calls += "downloadUrl"
            return PresignedUrl("https://storage.example/download?$SIGNATURE", EXPIRY)
        }

        override suspend fun delete(key: ObjectKey) {
            calls += "delete"
        }

        override suspend fun stat(key: ObjectKey): StoredObject? {
            calls += "stat"
            return null
        }
    }

    private companion object {
        const val SIGNATURE = "signature=not-a-real-signature"
        const val PART_URL = "https://storage.example/upload/part-1?$SIGNATURE"
        val EXPIRY: Instant = Instant.parse("2026-03-01T09:15:00Z")
        const val CONTENT_TYPE = "application/pdf"
        val PAYLOAD: ByteArray = "slides".encodeToByteArray()
        val PAYLOAD_HASH = ContentHash(InMemoryObjectStore.sha256Hex(PAYLOAD))
        val REQUEST = UploadRequest.ofMaterial(PAYLOAD_HASH, PAYLOAD.size.toLong(), CONTENT_TYPE)
    }
}
