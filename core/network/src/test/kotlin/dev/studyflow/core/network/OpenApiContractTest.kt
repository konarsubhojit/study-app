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
    private val specification: String = run {
        val file = File(SPEC_PATH)
        assertTrue(file.exists(), "Missing API specification at ${file.absolutePath}")
        file.readText()
    }

    @Test
    fun `every endpoint the client calls is described by the specification`() {
        assertAll(
            ApiEndpoint.entries.map { endpoint ->
                {
                    val pathBlock = specification
                        .substringAfter("\n  ${endpoint.path}:\n", missingDelimiterValue = "")
                        .substringBefore("\n\n")
                    assertTrue(pathBlock.isNotEmpty(), "${endpoint.path} is not described in $SPEC_PATH")
                    assertTrue(
                        "\n$pathBlock".contains("\n    ${endpoint.method}:\n"),
                        "${endpoint.method.uppercase()} ${endpoint.path} is not described in $SPEC_PATH",
                    )
                }
            },
        )
    }

    @Test
    fun `the specification documents the version the client speaks`() {
        assertTrue(
            specification.contains("/$API_VERSION/"),
            "The specification does not describe any $API_VERSION endpoint",
        )
    }

    @Test
    fun `the specification documents the headers the client relies on`() {
        assertTrue(
            specification.contains(MINIMUM_CLIENT_VERSION_HEADER),
            "The force-upgrade header is part of the contract and must be documented",
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
    }
}
