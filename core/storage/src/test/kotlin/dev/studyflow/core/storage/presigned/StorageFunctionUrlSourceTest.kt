package dev.studyflow.core.storage.presigned

import dev.studyflow.core.domain.materials.CloudStorageLimits
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStoreException
import dev.studyflow.core.storage.PartChecksums
import dev.studyflow.core.storage.UploadRequest
import dev.studyflow.core.storage.UploadedPart
import dev.studyflow.core.storage.assertFailsWith
import dev.studyflow.core.storage.local.InMemoryObjectStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@DisplayName("StorageFunctionUrlSource")
class StorageFunctionUrlSourceTest {
    private val requests = mutableListOf<Pair<String, JsonObject?>>()

    @Test
    fun `opening an upload sends the content hash and one checksum per planned part, never a key`() =
        runTest {
            val payload = ByteArray((CloudStorageLimits.PART_SIZE_BYTES + 1).toInt()) { it.toByte() }
            val request = requestFor(payload)
            val source = source { respondJson(initResponse(request.sizeBytes)) }

            val session = source.createUpload(request)

            val (url, body) = requests.single()
            assertEquals("$BASE_URL/initUpload", url)
            assertEquals(request.contentHash.hex, body?.string("contentHash"))
            assertEquals("application/pdf", body?.string("contentType"))
            assertEquals(request.sizeBytes.toString(), body?.get("sizeBytes")?.jsonPrimitive?.content)
            assertFalse(body.toString().contains("materials/"), "the server derives the key from the user")
            val checksums =
                body
                    ?.get("partChecksums")
                    ?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    .orEmpty()
            assertEquals(2, checksums.size)
            checksums.forEach { assertTrue(SHA256_BASE64.matches(it), it) }
            assertEquals(PartChecksums.sha256Base64(payload.copyOfRange(0, PART)), checksums[0])
            assertEquals(PartChecksums.sha256Base64(payload.copyOfRange(PART, payload.size)), checksums[1])
            assertEquals(ObjectKey.ofMaterial(request.contentHash), session.key)
            assertEquals("upload-1", session.uploadId)
        }

    @Test
    fun `each signed part carries the headers its signature covers`() =
        runTest {
            val request = requestFor(PAYLOAD)
            val source = source { respondJson(initResponse(request.sizeBytes)) }

            val part = source.createUpload(request).parts.single()

            assertEquals(mapOf("x-amz-checksum-sha256" to CHECKSUM), part.requiredHeaders)
            assertEquals("$PART_URL&n=1&X-Amz-Signature=sig-1", part.url.url)
            assertEquals(PAYLOAD.size.toLong(), part.size)
        }

    @Test
    fun `a request without the checksums the function demands never leaves the device`() =
        runTest {
            val source = source { respondJson("{}") }

            assertFailsWith<IllegalArgumentException> {
                source.createUpload(requestFor(PAYLOAD).copy(partChecksums = emptyList()))
            }

            assertTrue(requests.isEmpty())
        }

    @Test
    fun `the part count matches the function's ceil of size over part size`() {
        assertEquals(1, StorageFunctionUrlSource.expectedPartCount(1))
        assertEquals(1, StorageFunctionUrlSource.expectedPartCount(CloudStorageLimits.PART_SIZE_BYTES))
        assertEquals(2, StorageFunctionUrlSource.expectedPartCount(CloudStorageLimits.PART_SIZE_BYTES + 1))
        assertEquals(7, StorageFunctionUrlSource.expectedPartCount(CloudStorageLimits.MAX_SIZE_BYTES))
    }

    @Test
    fun `completion sends every part's entity tag in order`() =
        runTest {
            val request = requestFor(PAYLOAD)
            val source =
                source { data ->
                    if (data.url.encodedPath.endsWith("initUpload")) {
                        respondJson(initResponse(request.sizeBytes))
                    } else {
                        respondJson(
                            """{"contentHash":"${request.contentHash.hex}",""" +
                                """"contentType":"application/pdf","sizeBytes":${PAYLOAD.size}}""",
                        )
                    }
                }
            val session = source.createUpload(request)

            val stored = source.finishUpload(session, listOf(UploadedPart(1, "etag-1", PAYLOAD.size.toLong())))

            val body = requests.last().second
            assertEquals("upload-1", body?.string("uploadId"))
            assertEquals(
                "etag-1",
                body
                    ?.get("parts")
                    ?.jsonArray
                    ?.single()
                    ?.jsonObject
                    ?.string("etag"),
            )
            assertEquals(request.key, stored.key)
            assertEquals(request.contentHash, stored.contentHash)
        }

    @Test
    fun `stat of an object the function cannot find is absent, not an error`() =
        runTest {
            val source = source { respondJson(ERROR_BODY.format("object_not_found"), HttpStatusCode.NotFound) }

            assertNull(source.stat(ObjectKey.ofMaterial(HASH)))
            assertEquals("$BASE_URL/stat", requests.single().first)
        }

    @Test
    fun `stat of a present object reports what the function stored`() =
        runTest {
            val source =
                source {
                    respondJson(
                        """{"contentHash":"${HASH.hex}","contentType":"image/png","sizeBytes":12,""" +
                            """"updatedAt":"2026-03-01T09:00:00+00:00"}""",
                    )
                }

            val stored = source.stat(ObjectKey.ofMaterial(HASH))

            assertEquals(12L, stored?.sizeBytes)
            assertEquals(Instant.parse("2026-03-01T09:00:00Z"), stored?.updatedAt)
        }

    @Test
    fun `a key outside the materials namespace is never sent to the function`() =
        runTest {
            val source = source { respondJson("{}") }
            val thumbnail = ObjectKey("thumbnails/${HASH.hex}/256")

            assertNull(source.stat(thumbnail))
            source.delete(thumbnail)
            assertFailsWith<ObjectStoreException.NotFound> { source.downloadUrl(thumbnail, 1.minutes) }

            assertTrue(requests.isEmpty())
        }

    @Test
    fun `a download URL never claims to outlive the lifetime the caller asked for`() =
        runTest {
            val source =
                source(clock = NOW) { respondJson("""{"url":"$PART_URL","expiresAt":"2026-03-01T09:10:00.000Z"}""") }

            val url = source.downloadUrl(ObjectKey.ofMaterial(HASH), 5.minutes)

            assertEquals(NOW + 5.minutes, url.expiresAt)
            assertEquals(HASH.hex, requests.single().second?.string("contentHash"))
        }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("failures")
    fun `every documented failure maps onto the store's vocabulary with the right retryability`(
        status: Int,
        code: String,
        expected: Class<out ObjectStoreException>,
        retryable: Boolean,
    ) = runTest {
        val source = source { respondJson(ERROR_BODY.format(code), HttpStatusCode.fromValue(status)) }

        val failure = assertFailsWith<ObjectStoreException> { source.createUpload(requestFor(PAYLOAD)) }

        assertEquals(expected, failure::class.java)
        assertEquals(retryable, failure.retryable)
    }

    @Test
    fun `a dropped connection is worth retrying and names nothing from the request`() =
        runTest {
            val source = source { throw IOException("Authorization: ******") }

            val failure = assertFailsWith<ObjectStoreException.Transient> { source.stat(ObjectKey.ofMaterial(HASH)) }

            assertFalse(failure.message.orEmpty().contains(TOKEN))
            assertNull(failure.cause)
        }

    @Test
    fun `a response without the documented shape is worth retrying`() =
        runTest {
            val source = source { respondJson("""{"uploadId":"upload-1"}""") }

            assertFailsWith<ObjectStoreException.Transient> { source.createUpload(requestFor(PAYLOAD)) }
        }

    @Test
    fun `bodies are JSON even through a client that defaults the content type, as the app's does`() =
        runTest {
            var contentType: ContentType? = null
            val client =
                HttpClient(
                    MockEngine { data ->
                        contentType = data.body.contentType
                        respondJson(ERROR_BODY.format("object_not_found"), HttpStatusCode.NotFound)
                    },
                ) {
                    defaultRequest {
                        contentType(ContentType.Application.Json)
                        header("X-Client-Version", "1.0.0")
                    }
                }

            StorageFunctionUrlSource(client, BASE_URL).stat(ObjectKey.ofMaterial(HASH))

            assertEquals(ContentType.Application.Json, contentType?.withoutParameters())
        }

    @Test
    fun `an expired part URL is recovered by re-opening the same upload, with its required headers`() =
        runTest {
            val request = requestFor(PAYLOAD)
            val puts = mutableListOf<HttpRequestData>()
            var initCalls = 0
            val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { data ->
                when {
                    data.method == HttpMethod.Put -> {
                        puts += data
                        if (puts.size == 1) {
                            respond("<Error><Code>AccessDenied</Code></Error>", HttpStatusCode.Forbidden)
                        } else {
                            respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"etag-1\""))
                        }
                    }

                    data.url.encodedPath.endsWith("initUpload") -> {
                        initCalls += 1
                        respondJson(initResponse(request.sizeBytes, signature = "sig-$initCalls"))
                    }

                    else -> {
                        respondJson(
                            """{"contentHash":"${request.contentHash.hex}",""" +
                                """"contentType":"application/pdf","sizeBytes":${PAYLOAD.size}}""",
                        )
                    }
                }
            }
            val engine = MockEngine(handler)
            val store =
                PresignedObjectStore(
                    client = HttpClient(engine),
                    urls = StorageFunctionUrlSource(HttpClient(engine), BASE_URL),
                    clock = { NOW },
                )

            val session = store.initUpload(request)
            val uploaded = store.uploadPart(session, session.parts.single(), PAYLOAD)
            store.completeUpload(session, listOf(uploaded))

            assertEquals(2, initCalls)
            assertEquals(listOf("sig-1", "sig-2"), puts.map { it.url.parameters["X-Amz-Signature"] })
            puts.forEach { assertEquals(CHECKSUM, it.headers["x-amz-checksum-sha256"]) }
            puts.forEach { assertNull(it.headers[HttpHeaders.Authorization]) }
            assertEquals("etag-1", uploaded.etag)
        }

    private fun source(
        clock: Instant = NOW,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): StorageFunctionUrlSource =
        StorageFunctionUrlSource(
            client =
                HttpClient(
                    MockEngine { data ->
                        val text = (data.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
                        requests += data.url.toString() to text?.let { Json.parseToJsonElement(it).jsonObject }
                        handler(data)
                    },
                ),
            baseUrl = "$BASE_URL/",
            clock = { clock },
        )

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun JsonObject.string(name: String): String? = get(name)?.jsonPrimitive?.content

    private companion object {
        const val BASE_URL = "https://project.supabase.co/functions/v1/storage"
        const val PART_URL = "https://bucket.example/materials/owner/hash?partNumber=1"
        const val TOKEN = "eyJ.user.jwt"
        const val CHECKSUM = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="
        const val ERROR_BODY = """{"code":"%s","message":"Something went wrong."}"""
        const val PART = 8 * 1024 * 1024
        val SHA256_BASE64 = Regex("^[A-Za-z0-9+/]{43}=$")
        val NOW: Instant = Instant.parse("2026-03-01T09:00:00Z")
        val PAYLOAD: ByteArray = "slides".encodeToByteArray()
        val HASH = ContentHash(InMemoryObjectStore.sha256Hex(PAYLOAD))

        fun requestFor(payload: ByteArray): UploadRequest {
            val checksums =
                (0 until StorageFunctionUrlSource.expectedPartCount(payload.size.toLong())).map { index ->
                    val start = index * PART
                    PartChecksums.sha256Base64(payload.copyOfRange(start, minOf(payload.size, start + PART)))
                }
            return UploadRequest.ofMaterial(
                contentHash = ContentHash(InMemoryObjectStore.sha256Hex(payload)),
                sizeBytes = payload.size.toLong(),
                contentType = "application/pdf",
                partChecksums = checksums,
            )
        }

        fun initResponse(
            sizeBytes: Long,
            signature: String = "sig-1",
        ): String {
            val parts =
                (0 until StorageFunctionUrlSource.expectedPartCount(sizeBytes)).joinToString(",") { index ->
                    val offset = index.toLong() * PART
                    val size = minOf(PART.toLong(), sizeBytes - offset)
                    """{"number":${index + 1},"offset":$offset,"size":$size,""" +
                        """"url":"$PART_URL&n=${index + 1}&X-Amz-Signature=$signature",""" +
                        """"expiresAt":"2026-03-01T09:10:00.000Z",""" +
                        """"requiredHeaders":{"x-amz-checksum-sha256":"$CHECKSUM"}}"""
                }
            return """{"uploadId":"upload-1","expiresAt":"2026-03-02T09:00:00.000+00:00","parts":[$parts]}"""
        }

        @JvmStatic
        fun failures(): List<Arguments> =
            listOf(
                Arguments.of(400, "invalid_part_checksums", ObjectStoreException.AccessDenied::class.java, false),
                Arguments.of(401, "authentication_required", ObjectStoreException.Transient::class.java, true),
                Arguments.of(404, "upload_not_found", ObjectStoreException.Transient::class.java, true),
                Arguments.of(404, "endpoint_not_found", ObjectStoreException.Transient::class.java, true),
                Arguments.of(409, "upload_initializing", ObjectStoreException.Transient::class.java, true),
                Arguments.of(409, "object_already_exists", ObjectStoreException.Transient::class.java, true),
                Arguments.of(413, "quota_exceeded", ObjectStoreException.QuotaExceeded::class.java, false),
                Arguments.of(413, "file_too_large", ObjectStoreException.AccessDenied::class.java, false),
                Arguments.of(415, "unsupported_media_type", ObjectStoreException.AccessDenied::class.java, false),
                Arguments.of(422, "integrity_mismatch", ObjectStoreException.Integrity::class.java, false),
                Arguments.of(422, "scan_rejected", ObjectStoreException.Integrity::class.java, false),
                Arguments.of(429, "rate_limited", ObjectStoreException.Transient::class.java, true),
                Arguments.of(503, "storage_unavailable", ObjectStoreException.Transient::class.java, true),
            )
    }
}
