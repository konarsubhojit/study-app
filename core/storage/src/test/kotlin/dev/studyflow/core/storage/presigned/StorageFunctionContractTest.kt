package dev.studyflow.core.storage.presigned

import dev.studyflow.core.domain.materials.CloudStorageLimits
import dev.studyflow.core.domain.materials.MultipartLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Holds the client's upload limits to the ones the deployed `storage` function enforces.
 *
 * A part size that drifts from the function's `PART_SIZE_BYTES` changes how many checksums the
 * client sends, and the function rejects every such upload with `400 invalid_part_checksums` —
 * so a divergence has to fail here, at build time, rather than on every user's phone.
 */
@DisplayName("storage function contract")
class StorageFunctionContractTest {
    private val source: String =
        run {
            val file = File(FUNCTION_PATH)
            assertTrue(file.exists(), "Missing storage function at ${file.absolutePath}")
            file.readText()
        }

    @Test
    fun `the client plans parts of exactly the size the function checksums`() {
        assertEquals(constant("PART_SIZE_BYTES"), CloudStorageLimits.PART_SIZE_BYTES)
        assertEquals(CloudStorageLimits.PART_SIZE_BYTES, MultipartLimits.S3_COMPATIBLE.minPartSizeBytes)
    }

    @Test
    fun `the client refuses files the function would refuse, at import`() {
        assertEquals(constant("MAX_SIZE_BYTES"), CloudStorageLimits.MAX_SIZE_BYTES)
        assertEquals(allowedMimeTypes(), CloudStorageLimits.ALLOWED_MIME_TYPES)
    }

    @Test
    fun `every operation the client calls is routed by the function`() {
        val operations =
            Regex("""const OPERATIONS = new Set\(\[([^\]]*)]\)""")
                .find(source)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        listOf(
            StorageFunctionUrlSource.OP_INIT,
            StorageFunctionUrlSource.OP_COMPLETE,
            StorageFunctionUrlSource.OP_DOWNLOAD,
            StorageFunctionUrlSource.OP_DELETE,
            StorageFunctionUrlSource.OP_STAT,
        ).forEach { operation ->
            assertTrue(operations.contains("\"$operation\""), "'$operation' is not routed by $FUNCTION_PATH")
        }
    }

    /** Evaluates `const NAME = a * b * c;` — the only form the function uses for its limits. */
    private fun constant(name: String): Long {
        val expression =
            Regex("""const $name = ([0-9 *]+);""").find(source)?.groupValues?.get(1)
                ?: error("$name is not declared as a product of integers in $FUNCTION_PATH")
        return expression.split('*').map { it.trim().toLong() }.reduce(Long::times)
    }

    private fun allowedMimeTypes(): Set<String> {
        val body =
            Regex("""const ALLOWED_MIME_TYPES = new Set\(\[([^\]]*)]\)""").find(source)?.groupValues?.get(1)
                ?: error("ALLOWED_MIME_TYPES is not declared in $FUNCTION_PATH")
        return Regex("\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toSet()
    }

    private companion object {
        const val FUNCTION_PATH = "../../infra/supabase/functions/storage/index.ts"
    }
}
