package dev.studyflow.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models, hand-written against `docs/api/openapi.yaml` (issue #63).
 *
 * They are deliberately separate from `:core:model`: the domain model is ours to change, the wire
 * format is the server's. Keeping them apart means a server-side rename is a mapping change in one
 * file rather than a refactor across the app.
 *
 * Nullable properties are the spec's optional fields. Every unknown field is dropped by
 * [StudyFlowJson], so a server that starts sending more data cannot break an installed client.
 */
@Serializable
public data class ApiErrorDto(
    val code: String,
    val message: String = "",
    val details: Map<String, String> = emptyMap(),
)

/** Request body of `POST /v1/auth/refresh`. */
@Serializable
public data class RefreshRequestDto(
    val refreshToken: String,
)

/** Response body of `POST /v1/auth/refresh`. */
@Serializable
public data class AuthTokensDto(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

/** An element of `GET /v1/subjects`. */
@Serializable
public data class SubjectDto(
    val id: String,
    val name: String,
    val colorHex: String? = null,
)

/** An element of `GET /v1/tasks`. */
@Serializable
public data class TaskDto(
    val id: String,
    val subjectId: String,
    val title: String,
    val completed: Boolean = false,
    @SerialName("dueAt") val dueAtIso: String? = null,
)

/** Body and response of `POST /v1/sessions`. */
@Serializable
public data class StudySessionDto(
    val id: String,
    val subjectId: String,
    @SerialName("startedAt") val startedAtIso: String,
    @SerialName("endedAt") val endedAtIso: String? = null,
    val focusedSeconds: Long,
)
