package dev.studyflow.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire models, hand-written against `docs/api/openapi.yaml` (issue #63).
//
// They are deliberately separate from `:core:model`: the domain model is ours to change, the wire
// format is the server's. Keeping them apart means a server-side rename is a mapping change in one
// file rather than a refactor across the app.
//
// Nullable properties are the spec's optional fields. Every unknown field is dropped by
// StudyFlowJson, so a server that starts sending more data cannot break an installed client.

/** The structured error body any operation can return. */
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

/**
 * One study session as it crosses the wire, for both directions of `/v1/sync/sessions`
 * (issue #55).
 *
 * Carries the append-only event log next to the projection because the log is the source of truth
 * and the projection is only a fold of it: a receiver that got one without the other would have to
 * invent the missing half. [deviceId] and [updatedAtIso] travel too — they are the two terms of
 * the conflict rule, and a device cannot resolve a conflict deterministically without both.
 */
@Serializable
public data class SyncSessionDto(
    val id: String,
    val deviceId: String,
    @SerialName("updatedAt") val updatedAtIso: String,
    @SerialName("startedAt") val startedAtIso: String,
    @SerialName("endedAt") val endedAtIso: String? = null,
    val status: String,
    val subjectId: String? = null,
    val taskId: String? = null,
    val note: String? = null,
    /** A tombstone rather than an absence, so a deletion replicates instead of being resurrected. */
    val deleted: Boolean = false,
    val manualOverride: Boolean = false,
    /** The folded duration. Authoritative for a [manualOverride] session, which has no log to replay. */
    val countedMillis: Long = 0,
    /** Time the sending device could not vouch for; never silently folded into [countedMillis]. */
    val unverifiedMillis: Long = 0,
    val events: List<SyncSessionEventDto> = emptyList(),
)

/** One entry of a session's append-only log. */
@Serializable
public data class SyncSessionEventDto(
    val id: String,
    val sessionId: String,
    val type: String,
    val sequence: Long,
    @SerialName("wallClock") val wallClockIso: String,
    val uptimeMillis: Long,
    val bootId: String,
)

/** Request body of `POST /v1/sync/sessions`. */
@Serializable
public data class SyncPushRequestDto(
    val deviceId: String,
    val changes: List<SyncSessionDto>,
)

/**
 * Response body of `POST /v1/sync/sessions`.
 *
 * A rejected id is not an error: it means the server holds something strictly newer, which the
 * client learns in full from the next delta rather than by arguing about it here.
 */
@Serializable
public data class SyncPushResponseDto(
    val acceptedIds: List<String> = emptyList(),
    val rejectedIds: List<String> = emptyList(),
)

/**
 * Response body of `GET /v1/sync/sessions`.
 *
 * [nextCursor] is opaque to the client: it is stored verbatim and sent back, which is what lets
 * the server change how it paginates without an app release.
 */
@Serializable
public data class SyncDeltaDto(
    val changes: List<SyncSessionDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

/**
 * Response body of `DELETE /v1/account`.
 *
 * The retention window travels with the receipt rather than being a constant in the app: it is a
 * promise the *server* makes, and a client that hard-coded it would keep showing the old number
 * after the policy changed.
 */
@Serializable
public data class AccountDeletionReceiptDto(
    @SerialName("acceptedAt") val acceptedAtIso: String? = null,
    val retentionWindowDays: Int = 0,
)
