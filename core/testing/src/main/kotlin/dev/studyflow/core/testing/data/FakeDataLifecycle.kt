package dev.studyflow.core.testing.data

import dev.studyflow.core.domain.lifecycle.AccountDeletionReceipt
import dev.studyflow.core.domain.lifecycle.ArchiveSink
import dev.studyflow.core.domain.lifecycle.ArchiveSinkFactory
import dev.studyflow.core.domain.lifecycle.ArchiveSource
import dev.studyflow.core.domain.lifecycle.ArchiveSourceFactory
import dev.studyflow.core.domain.lifecycle.DataEraser
import dev.studyflow.core.domain.lifecycle.ImportBatch
import dev.studyflow.core.domain.lifecycle.LocalDataReader
import dev.studyflow.core.domain.lifecycle.LocalDataSnapshot
import dev.studyflow.core.domain.lifecycle.LocalDataWriter
import dev.studyflow.core.domain.lifecycle.MaterialFileStore
import dev.studyflow.core.domain.lifecycle.RemoteAccountEraser
import dev.studyflow.core.domain.lifecycle.SessionWithLog
import dev.studyflow.core.domain.materials.ArchiveEntryMetadata
import dev.studyflow.core.domain.result.DomainError
import dev.studyflow.core.domain.result.DomainResult
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * An in-memory stand-in for the Storage Access Framework and the zip layer behind it.
 *
 * An export followed by an import through the *same* fake is what makes the round trip testable on
 * the JVM in milliseconds, and [corrupt] lets a test hand the importer the archive a hostile file
 * would be — a traversal path, an entry that lies about its size — without building a real zip.
 */
public class FakeArchiveStorage {
    /** Written archives, keyed by the URI the export was pointed at. */
    public val archives: MutableMap<String, MutableMap<String, ByteArray>> = mutableMapOf()

    /** When set, opening a destination or a source fails the way a revoked URI does. */
    public var failToOpen: Boolean = false

    /** The export half; the bytes it writes are the bytes [sourceFactory] reads back. */
    public val sinkFactory: ArchiveSinkFactory =
        ArchiveSinkFactory { destinationUri ->
            if (failToOpen) throw IOException("destination unavailable")
            val entries = archives.getOrPut(destinationUri) { linkedMapOf() }
            object : ArchiveSink {
                override suspend fun writeEntry(
                    path: String,
                    content: InputStream,
                ) {
                    entries[path] = content.readBytes()
                }

                override suspend fun writeText(
                    path: String,
                    text: String,
                ) {
                    entries[path] = text.encodeToByteArray()
                }

                override fun close(): Unit = Unit
            }
        }

    /** The import half. */
    public val sourceFactory: ArchiveSourceFactory =
        ArchiveSourceFactory { sourceUri ->
            if (failToOpen) throw IOException("source unavailable")
            val entries = archives[sourceUri] ?: throw IOException("no archive at $sourceUri")
            object : ArchiveSource {
                override suspend fun entries(): List<ArchiveEntryMetadata> =
                    entries.map { (name, bytes) ->
                        ArchiveEntryMetadata(
                            name = name,
                            declaredSize = bytes.size.toLong(),
                            compressedSize = bytes.size.toLong(),
                        )
                    }

                override suspend fun open(entryName: String): InputStream =
                    entries[entryName]?.let(::ByteArrayInputStream) ?: throw IOException("no entry $entryName")

                override fun close(): Unit = Unit
            }
        }

    /** Replaces, adds or removes one entry, so a test can describe a tampered-with archive. */
    public fun corrupt(
        uri: String,
        entry: String,
        content: ByteArray?,
    ) {
        val entries = archives.getOrPut(uri) { linkedMapOf() }
        if (content == null) entries.remove(entry) else entries[entry] = content
    }
}

/** In-memory [LocalDataReader] and [LocalDataWriter], upserting by id exactly as Room does. */
public class FakeLocalDataStore(
    public var deviceId: String = TEST_DEVICE_ID,
) : LocalDataReader,
    LocalDataWriter {
    public val subjects: MutableList<Subject> = mutableListOf()
    public val sessions: MutableList<SessionWithLog> = mutableListOf()
    public val tasks: MutableList<StudyTask> = mutableListOf()
    public val materials: MutableList<Material> = mutableListOf()

    /** Batches the importer applied, so a test can assert that a skip really wrote nothing. */
    public val applied: MutableList<ImportBatch> = mutableListOf()

    override suspend fun readSnapshot(): LocalDataSnapshot =
        LocalDataSnapshot(
            deviceId = deviceId,
            subjects = subjects.toList(),
            sessions = sessions.toList(),
            tasks = tasks.toList(),
            materials = materials.toList(),
        )

    override suspend fun applyImport(batch: ImportBatch) {
        applied += batch
        batch.subjects.forEach { subject -> subjects.upsert(subject) { it.id == subject.id } }
        batch.sessions.forEach { session -> sessions.upsert(session) { it.session.id == session.session.id } }
        batch.tasks.forEach { task -> tasks.upsert(task) { it.id == task.id } }
        batch.materials.forEach { material -> materials.upsert(material) { it.id == material.id } }
    }

    private fun <T> MutableList<T>.upsert(
        value: T,
        matches: (T) -> Boolean,
    ) {
        val index = indexOfFirst(matches)
        if (index >= 0) set(index, value) else add(value)
    }
}

/** In-memory [MaterialFileStore]; paths are opaque keys, as they are to the domain. */
public class FakeMaterialFileStore : MaterialFileStore {
    public val files: MutableMap<String, ByteArray> = mutableMapOf()

    override suspend fun open(localPath: String): InputStream? = files[localPath]?.let(::ByteArrayInputStream)

    override suspend fun store(
        materialId: String,
        content: InputStream,
    ): String {
        val path = "materials/$materialId"
        files[path] = content.readBytes()
        return path
    }
}

/** A [RemoteAccountEraser] whose answer — and whose refusal — a test chooses. */
public class FakeRemoteAccountEraser(
    public var result: DomainResult<AccountDeletionReceipt> =
        DomainResult.Success(
            AccountDeletionReceipt(
                acceptedAt = "2026-09-23T02:49:21Z",
                retentionWindowDays = AccountDeletionReceipt.DEFAULT_RETENTION_WINDOW_DAYS,
            ),
        ),
) : RemoteAccountEraser {
    public var calls: Int = 0
        private set

    override suspend fun deleteAccount(): DomainResult<AccountDeletionReceipt> {
        calls++
        return result
    }

    /** Makes the server refuse, which must leave every local store untouched. */
    public fun failWith(error: DomainError) {
        result = DomainResult.Failure(error)
    }
}

/** A [DataEraser] that records whether it ran, and can refuse. */
public class RecordingDataEraser(
    override val name: String,
    private val failure: Throwable? = null,
) : DataEraser {
    public var erasures: Int = 0
        private set

    override suspend fun erase() {
        erasures++
        failure?.let { throw it }
    }
}
