package dev.studyflow.core.scheduling

import dev.studyflow.core.common.logging.AppLogger
import dev.studyflow.core.common.time.DeviceIdProvider
import dev.studyflow.core.domain.sync.SyncDocument
import dev.studyflow.core.domain.sync.SyncDocumentCodec
import dev.studyflow.core.domain.sync.SyncDocumentPage
import dev.studyflow.core.domain.sync.SyncEntityType
import dev.studyflow.core.domain.sync.SyncFailure
import dev.studyflow.core.domain.sync.SyncPage
import dev.studyflow.core.domain.sync.SyncPushAck
import dev.studyflow.core.domain.sync.SyncResult
import dev.studyflow.core.domain.sync.SyncSessionRecord
import dev.studyflow.core.domain.sync.SyncTransport
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.SessionElapsed
import dev.studyflow.core.model.SessionEvent
import dev.studyflow.core.model.SessionEventType
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudySession
import dev.studyflow.core.model.TimeAnchor
import dev.studyflow.core.network.ApiResult
import dev.studyflow.core.network.StudyFlowApi
import dev.studyflow.core.network.error.ApiError
import dev.studyflow.core.network.model.SyncDeltaDto
import dev.studyflow.core.network.model.SyncPushRequestDto
import dev.studyflow.core.network.model.SyncPushResponseDto
import dev.studyflow.core.network.model.SyncRecordDeltaDto
import dev.studyflow.core.network.model.SyncRecordDto
import dev.studyflow.core.network.model.SyncRecordPushRequestDto
import dev.studyflow.core.network.model.SyncSessionDto
import dev.studyflow.core.network.model.SyncSessionEventDto
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The HTTP half of sync: `SyncSessionRecord` in, `/v1/sync/sessions` out (issue #55), and tasks
 * and materials through the generic `/v1/sync/records` stream, their payloads encoded by
 * [SyncDocumentCodec] so the wire format is the export format.
 *
 * Lives here rather than in `:core:network` because it speaks the domain's language on one side —
 * `:core:network` is deliberately a wire-format module with no knowledge of `:core:model`, and the
 * domain is deliberately free of HTTP. This module already owns that kind of seam for uploads and
 * reminders, so the translation belongs here too.
 *
 * An inbound change that cannot be parsed at all (a malformed timestamp, an event type this
 * version does not know) is dropped rather than failing the page: one unreadable row must not
 * wedge a device's delta forever, and the cursor moving past it is the only way out. Every drop is
 * logged, since silent data loss is the failure mode that would otherwise be invisible.
 */
public class ApiSyncTransport(
    private val api: StudyFlowApi,
    private val deviceIdProvider: DeviceIdProvider,
    private val logger: AppLogger,
) : SyncTransport {
    override suspend fun push(records: List<SyncSessionRecord>): SyncResult<SyncPushAck> {
        val request =
            SyncPushRequestDto(
                deviceId = deviceIdProvider.current(),
                changes = records.map { it.asSessionDto() },
            )
        return when (val result = api.pushSessionChanges(request)) {
            is ApiResult.Success -> SyncResult.Success(result.value.asAck())
            is ApiResult.Failure -> SyncResult.Failure(result.error.asSyncFailure())
        }
    }

    override suspend fun pull(
        cursor: String?,
        limit: Int,
    ): SyncResult<SyncPage> =
        when (val result = api.sessionChanges(cursor, limit)) {
            is ApiResult.Success -> SyncResult.Success(result.value.asPage())
            is ApiResult.Failure -> SyncResult.Failure(result.error.asSyncFailure())
        }

    override suspend fun pushDocuments(documents: List<SyncDocument>): SyncResult<SyncPushAck> {
        val request =
            SyncRecordPushRequestDto(
                deviceId = deviceIdProvider.current(),
                changes = documents.map { it.asRecordDto() },
            )
        return when (val result = api.pushRecordChanges(request)) {
            is ApiResult.Success -> SyncResult.Success(result.value.asAck())
            is ApiResult.Failure -> SyncResult.Failure(result.error.asSyncFailure())
        }
    }

    override suspend fun pullDocuments(
        cursor: String?,
        limit: Int,
    ): SyncResult<SyncDocumentPage> =
        when (val result = api.recordChanges(cursor, limit)) {
            is ApiResult.Success -> SyncResult.Success(result.value.asDocumentPage())
            is ApiResult.Failure -> SyncResult.Failure(result.error.asSyncFailure())
        }

    private fun SyncDocument.asRecordDto(): SyncRecordDto =
        SyncRecordDto(
            entityType = entityType.wireName(),
            id = id,
            deviceId = deviceId,
            updatedAtIso = updatedAt.toString(),
            deleted = deleted,
            schemaVersion = SyncDocumentCodec.SCHEMA_VERSION,
            payload = SyncDocumentCodec.encode(this),
        )

    private fun SyncRecordDeltaDto.asDocumentPage(): SyncDocumentPage =
        SyncDocumentPage(
            changes = changes.mapNotNull { it.asDocumentOrNull() },
            nextCursor = nextCursor,
            hasMore = hasMore,
        )

    private fun SyncRecordDto.asDocumentOrNull(): SyncDocument? =
        runCatching {
            val type =
                requireNotNull(WIRE_TYPES.entries.firstOrNull { it.value == entityType }?.key) {
                    "unknown entity type $entityType"
                }
            SyncDocumentCodec.decode(
                entityType = type,
                id = id,
                deviceId = deviceId,
                updatedAt = Instant.parse(updatedAtIso),
                deleted = deleted,
                schemaVersion = schemaVersion,
                payload = payload,
            )
        }.onFailure { error ->
            logger.warning(TAG, "Dropping unreadable inbound $entityType $id: ${error.message}")
        }.getOrNull()

    private fun SyncEntityType.wireName(): String =
        requireNotNull(WIRE_TYPES[this]) { "$this is not carried by the record stream" }

    private fun SyncPushResponseDto.asAck(): SyncPushAck =
        SyncPushAck(acceptedIds = acceptedIds.toSet(), rejectedIds = rejectedIds.toSet())

    private fun SyncDeltaDto.asPage(): SyncPage =
        SyncPage(
            changes = changes.mapNotNull { it.asRecordOrNull() },
            nextCursor = nextCursor,
            hasMore = hasMore,
        )

    private fun SyncSessionDto.asRecordOrNull(): SyncSessionRecord? =
        runCatching {
            val session =
                StudySession(
                    id = id,
                    subjectId = subjectId,
                    taskId = taskId,
                    note = note,
                    startedAt = Instant.parse(startedAtIso),
                    endedAt = endedAtIso?.let(Instant::parse),
                    status = SessionStatus.valueOf(status),
                    elapsed =
                        SessionElapsed(
                            counted = countedMillis.milliseconds,
                            unverified = unverifiedMillis.milliseconds,
                        ),
                    deviceId = deviceId,
                    updatedAt = Instant.parse(updatedAtIso),
                    deleted = deleted,
                    manualOverride = manualOverride,
                )
            SyncSessionRecord(
                session = session,
                events = events.map { it.asEvent(sessionId = session.id, deviceId = session.deviceId) },
            )
        }.onFailure { error ->
            logger.warning(TAG, "Dropping unreadable inbound session $id: ${error.message}")
        }.getOrNull()

    /**
     * Rebuilds a local event from a replicated one.
     *
     * The server discards [SyncSessionEventDto.uptimeMillis] and [SyncSessionEventDto.bootId], so
     * an inbound event usually arrives without them and an anchor has to be synthesised. It is
     * deliberately made unique per event: a shared placeholder boot id would let
     * [TimeAnchor.uptimeDurationTo] report a confident zero between two events from another
     * handset, silently presenting an invented monotonic measurement as a real one. Being
     * incomparable is the honest answer, and it is what makes the replicated log fall back to the
     * wall clock it does carry.
     */
    private fun SyncSessionEventDto.asEvent(
        sessionId: String,
        deviceId: String,
    ): SessionEvent =
        SessionEvent(
            id = id,
            sessionId = sessionId,
            type = SessionEventType.valueOf(type),
            anchor =
                TimeAnchor(
                    uptime = (uptimeMillis ?: 0L).milliseconds,
                    wallClock = Instant.parse(wallClockIso),
                    bootId = BootId(bootId ?: "$REPLICATED_BOOT_PREFIX$deviceId:$id"),
                ),
            sequence = sequence,
        )

    /**
     * Whether another attempt could plausibly succeed.
     *
     * Only the failures that are genuinely about *this* payload are permanent: retrying a rejected
     * or unauthorised request forever would burn battery and never converge, while retrying a
     * network or server error is exactly how offline-first is supposed to behave.
     */
    private fun ApiError.asSyncFailure(): SyncFailure =
        when (this) {
            is ApiError.Offline,
            is ApiError.Timeout,
            is ApiError.RateLimited,
            is ApiError.Server,
            -> SyncFailure(message = message.defaultText, retryable = true)

            is ApiError.Unauthorized,
            is ApiError.Forbidden,
            is ApiError.NotFound,
            is ApiError.Conflict,
            is ApiError.UpgradeRequired,
            is ApiError.Malformed,
            is ApiError.Unexpected,
            -> SyncFailure(message = message.defaultText, retryable = false)
        }

    private fun SyncSessionRecord.asSessionDto(): SyncSessionDto =
        SyncSessionDto(
            id = session.id,
            deviceId = session.deviceId,
            updatedAtIso = session.updatedAt.toString(),
            startedAtIso = session.startedAt.toString(),
            endedAtIso = session.endedAt?.toString(),
            status = session.status.name,
            subjectId = session.subjectId,
            taskId = session.taskId,
            note = session.note,
            deleted = session.deleted,
            manualOverride = session.manualOverride,
            countedMillis = session.elapsed.counted.inWholeMilliseconds,
            unverifiedMillis = session.elapsed.unverified.inWholeMilliseconds,
            events = events.map { it.asEventDto() },
        )

    private fun SessionEvent.asEventDto(): SyncSessionEventDto =
        SyncSessionEventDto(
            id = id,
            sessionId = sessionId,
            type = type.name,
            sequence = sequence,
            wallClockIso = anchor.wallClock.toString(),
            uptimeMillis = anchor.uptime.inWholeMilliseconds,
            bootId = anchor.bootId.value,
        )

    private companion object {
        const val TAG = "ApiSyncTransport"

        /** Marks a boot id this device invented for a replicated event rather than observed. */
        const val REPLICATED_BOOT_PREFIX = "replicated:"

        /** The record stream's `entityType` values; sessions travel on their own stream. */
        val WIRE_TYPES: Map<SyncEntityType, String> =
            mapOf(SyncEntityType.TASK to "task", SyncEntityType.MATERIAL to "material")
    }
}
