package dev.studyflow.core.network

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.io.File

/**
 * Holds the client to the checked-in contract (issue #63).
 *
 * `docs/api/openapi.yaml` is the source of truth, so an endpoint the client calls but nobody
 * documented — or a path typo — has to fail here, at build time, rather than as a 404 on a user's
 * phone.
 */
@DisplayName("OpenAPI contract")
class OpenApiContractTest {
    private val specification: String =
        run {
            val file = File(SPEC_PATH)
            assertTrue(file.exists(), "Missing API specification at ${file.absolutePath}")
            file.readText()
        }

    /** Documented operations, as `path` to the HTTP methods declared under it. */
    private val documentedOperations: Map<String, Set<String>> = parseOperations(specification)

    @Test
    fun `every endpoint the client calls is described by the specification`() {
        assertAll(
            ApiEndpoint.entries.map { endpoint ->
                {
                    val methods = documentedOperations[endpoint.path]
                    assertTrue(methods != null, "${endpoint.path} is not described in $SPEC_PATH")
                    assertTrue(
                        methods?.contains(endpoint.method) == true,
                        "${endpoint.method.uppercase()} ${endpoint.path} is not described in $SPEC_PATH",
                    )
                }
            },
        )
    }

    @Test
    fun `the specification only documents the version the client speaks`() {
        assertTrue(documentedOperations.isNotEmpty(), "No paths were found in $SPEC_PATH")
        assertTrue(
            documentedOperations.keys.all { it.startsWith("/$API_VERSION/") },
            "The specification describes paths outside $API_VERSION: ${documentedOperations.keys}",
        )
    }

    @Test
    fun `the specification documents the headers the client relies on`() {
        assertAll(
            {
                assertTrue(
                    specification.contains(MINIMUM_CLIENT_VERSION_HEADER),
                    "The force-upgrade header is part of the contract and must be documented",
                )
            },
            {
                assertTrue(
                    specification.contains(CLIENT_VERSION_HEADER),
                    "The client sends $CLIENT_VERSION_HEADER on every request, so it must be documented",
                )
            },
        )
    }

    @Test
    fun `the specification offers a local mock backend to run against`() {
        assertTrue(
            specification.contains("http://10.0.2.2:8080"),
            "The local mock backend server must stay documented so the app can be pointed at it",
        )
    }

    private companion object {
        /** Tests run with the module directory as the working directory. */
        const val SPEC_PATH = "../../docs/api/openapi.yaml"
        const val PATH_INDENT = 2
        const val METHOD_INDENT = 4

        val HTTP_METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

        /**
         * Reads the `paths:` section by indentation.
         *
         * A YAML parser would be a dependency for a single test; indentation is enough to extract
         * which methods sit under which path, and is not fooled by blank lines or ordering.
         */
        fun parseOperations(specification: String): Map<String, Set<String>> {
            val operations = mutableMapOf<String, MutableSet<String>>()
            var inPaths = false
            var currentPath: String? = null

            specification.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

                val indent = line.takeWhile(Char::isWhitespace).length
                val key = trimmed.removeSuffix(":").trim('\'', '"')
                if (indent == 0) {
                    inPaths = trimmed == "paths:"
                    return@forEach
                }
                if (!inPaths) return@forEach

                if (indent == PATH_INDENT && trimmed.endsWith(":")) {
                    currentPath = key
                    operations.getOrPut(key) { mutableSetOf() }
                } else if (indent == METHOD_INDENT && key in HTTP_METHODS) {
                    currentPath?.let { path -> operations.getValue(path) += key }
                }
            }
            return operations
        }
    }
}
