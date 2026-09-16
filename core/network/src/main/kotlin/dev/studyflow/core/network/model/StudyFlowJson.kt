package dev.studyflow.core.network.model

import kotlinx.serialization.json.Json

/**
 * The single [Json] configuration used for every request and response (issue #63).
 *
 * `ignoreUnknownKeys` is the forward-compatibility guarantee written down in
 * `docs/api/openapi.yaml`: the server may add a response field at any time, and an app that was
 * installed months earlier keeps working instead of failing to parse on the user's phone.
 * `ForwardCompatibilityTest` proves it.
 *
 * `explicitNulls = false` keeps absent and null indistinguishable, which matches the spec's
 * optional fields, and `encodeDefaults = false` keeps request bodies to what the caller actually
 * set.
 */
public val StudyFlowJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = false
}
