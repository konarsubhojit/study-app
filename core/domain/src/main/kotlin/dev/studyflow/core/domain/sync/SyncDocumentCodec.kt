package dev.studyflow.core.domain.sync

import dev.studyflow.core.domain.lifecycle.ArchiveFormatException
import dev.studyflow.core.domain.lifecycle.ArchivedMaterial
import dev.studyflow.core.domain.lifecycle.ArchivedTask
import dev.studyflow.core.domain.lifecycle.CURRENT_SCHEMA_VERSION
import dev.studyflow.core.domain.lifecycle.toArchived
import dev.studyflow.core.domain.lifecycle.toModel
import dev.studyflow.core.model.SyncState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

/**
 * Turns a [SyncDocument] into the JSON payload the record stream carries, and back (ADR 0018).
 *
 * The payload *is* the export format ([ArchivedTask], [ArchivedMaterial]) rather than a third
 * shape: the archive is already versioned, lossless and tested, and a record written by a newer
 * build of the same schema version may add fields this build ignores. Device-local state is
 * stripped on the way out, and the envelope's `id`, `updatedAt` and `deleted` are authoritative on
 * the way in, so a payload cannot disagree with the metadata the conflict rule compared.
 */
public object SyncDocumentCodec {
    /** The payload schema this build writes and the newest one it reads. */
    public const val SCHEMA_VERSION: Int = CURRENT_SCHEMA_VERSION

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    public fun encode(document: SyncDocument): JsonObject =
        when (document) {
            is SyncTaskRecord -> {
                val archived = document.task.toArchived()
                json
                    .encodeToJsonElement(
                        ArchivedTask.serializer(),
                        archived.copy(
                            reminders =
                                archived.reminders.map {
                                    it.copy(
                                        snoozeUntil = null,
                                        snoozeCount = null,
                                        lastFiredAt = null,
                                        schedulingId = null,
                                    )
                                },
                        ),
                    ).jsonObject
            }

            is SyncMaterialRecord -> {
                json
                    .encodeToJsonElement(
                        ArchivedMaterial.serializer(),
                        document.material.toArchived(archiveEntry = null).copy(pinnedForOffline = false),
                    ).jsonObject
            }
        }

    /**
     * Rebuilds a document from its envelope and payload.
     *
     * @throws ArchiveFormatException when the payload is malformed, invalid for its type, or written
     *   in a schema version newer than [SCHEMA_VERSION].
     */
    @Suppress("LongParameterList", "ThrowsCount") // Every failure is reported as the same exception type.
    public fun decode(
        entityType: SyncEntityType,
        id: String,
        deviceId: String,
        updatedAt: Instant,
        deleted: Boolean,
        schemaVersion: Int,
        payload: JsonObject,
    ): SyncDocument {
        if (schemaVersion !in 1..SCHEMA_VERSION) {
            throw ArchiveFormatException(
                "$entityType $id uses schema version $schemaVersion; this build reads $SCHEMA_VERSION",
            )
        }
        return try {
            when (entityType) {
                SyncEntityType.TASK -> {
                    val task =
                        json
                            .decodeFromJsonElement(ArchivedTask.serializer(), payload)
                            .copy(id = id, updatedAt = updatedAt.toString(), deleted = deleted)
                            .toModel()
                    SyncTaskRecord(task, deviceId)
                }

                SyncEntityType.MATERIAL -> {
                    val material =
                        json
                            .decodeFromJsonElement(ArchivedMaterial.serializer(), payload)
                            .copy(
                                id = id,
                                updatedAt = updatedAt.toString(),
                                deleted = deleted,
                                pinnedForOffline = false,
                            ).toModel(localPath = null)
                    SyncMaterialRecord(material.copy(sync = SyncState.Synced), deviceId)
                }

                SyncEntityType.SESSION -> {
                    throw ArchiveFormatException("sessions travel on the session stream, not as records")
                }
            }
        } catch (malformed: SerializationException) {
            throw ArchiveFormatException("$entityType $id is not a valid sync record", malformed)
        } catch (malformed: IllegalArgumentException) {
            throw ArchiveFormatException("$entityType $id is not a valid sync record", malformed)
        }
    }
}
