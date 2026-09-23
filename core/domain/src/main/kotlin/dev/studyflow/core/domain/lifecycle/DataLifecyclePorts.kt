package dev.studyflow.core.domain.lifecycle

import dev.studyflow.core.domain.materials.ArchiveEntryMetadata
import dev.studyflow.core.domain.result.DomainResult
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subject
import java.io.Closeable
import java.io.InputStream

// The platform seams of the data-lifecycle feature (issue #78).
//
// Everything with a rule in it — what an archive contains, which record wins a merge, in which
// order an account deletion erases things — lives in this package as plain Kotlin. Everything that
// needs a `ContentResolver`, a zip stream, Room, DataStore or an HTTP client is behind one of the
// interfaces below, mirroring the port style already used by the materials import pipeline
// (`ImportContentReader`) and by sync (`SyncTransport`).

/**
 * Writes the entries of an archive to a destination the user picked through the Storage Access
 * Framework.
 *
 * Entries are streamed rather than handed over as byte arrays: an export can contain several
 * hundred megabytes of lecture recordings, and the memory cost of writing one must not depend on
 * the size of the largest file in it.
 */
public interface ArchiveSink : Closeable {
    /** Appends one entry, copying [content] into it and closing it. */
    public suspend fun writeEntry(
        path: String,
        content: InputStream,
    )

    /** Appends one small text entry, such as the archive's JSON document. */
    public suspend fun writeText(
        path: String,
        text: String,
    )
}

/** Opens a user-picked `content://` destination for writing. */
public fun interface ArchiveSinkFactory {
    /** @throws java.io.IOException when the destination cannot be opened for writing. */
    public suspend fun open(destinationUri: String): ArchiveSink
}

/** Reads an archive the user picked, without deciding whether any entry is safe to trust. */
public interface ArchiveSource : Closeable {
    /** Metadata for every entry, read from the archive's directory before anything is extracted. */
    public suspend fun entries(): List<ArchiveEntryMetadata>

    /**
     * Opens one entry for reading.
     *
     * @throws java.io.IOException when the entry is absent or unreadable.
     */
    public suspend fun open(entryName: String): InputStream
}

/** Opens a user-picked `content://` archive for reading. */
public fun interface ArchiveSourceFactory {
    /** @throws java.io.IOException when the source cannot be opened for reading. */
    public suspend fun open(sourceUri: String): ArchiveSource
}

/** Everything an export puts in the archive, read from the local database in one pass. */
public data class LocalDataSnapshot(
    val deviceId: String,
    val subjects: List<Subject> = emptyList(),
    val sessions: List<SessionWithLog> = emptyList(),
    val tasks: List<StudyTask> = emptyList(),
    val materials: List<Material> = emptyList(),
)

/** The records an import decided to write, already resolved against what was there before. */
public data class ImportBatch(
    val subjects: List<Subject> = emptyList(),
    val sessions: List<SessionWithLog> = emptyList(),
    val tasks: List<StudyTask> = emptyList(),
    val materials: List<Material> = emptyList(),
) {
    public val isEmpty: Boolean
        get() = subjects.isEmpty() && sessions.isEmpty() && tasks.isEmpty() && materials.isEmpty()
}

/** Reads the whole local dataset, tombstones included, for export and for merge resolution. */
public fun interface LocalDataReader {
    public suspend fun readSnapshot(): LocalDataSnapshot
}

/**
 * Writes a resolved import.
 *
 * Implementations must apply the whole [ImportBatch] in a single transaction: a restore that is
 * interrupted halfway must either be entirely present or entirely absent, because the user's next
 * move after a failed restore is to try the same archive again.
 */
public fun interface LocalDataWriter {
    public suspend fun applyImport(batch: ImportBatch)
}

/**
 * Reads and writes the cached bytes of a material.
 *
 * The domain never learns where those bytes live — app-private storage on Android, a temporary
 * directory in a test — only that a material has a local path that can be opened and that new
 * bytes can be stored under a material id.
 */
public interface MaterialFileStore {
    /** Opens the cached file at [localPath], or returns `null` when it is no longer there. */
    public suspend fun open(localPath: String): InputStream?

    /**
     * Stores [content] as the cached copy of [materialId].
     *
     * @return the local path of the stored file.
     * @throws java.io.IOException when the bytes could not be stored.
     */
    public suspend fun store(
        materialId: String,
        content: InputStream,
    ): String
}

/**
 * One thing account deletion has to remove.
 *
 * Erasers are separate and named so that the coordinator can report exactly what it cleared, and
 * so a partial failure names the store that survived instead of a stack trace. Implementations
 * must be idempotent: erasing an already-empty store succeeds, because the user's retry after a
 * failure re-runs every eraser.
 */
public interface DataEraser {
    /** Stable, non-sensitive name used in logs and in the deletion report. */
    public val name: String

    /** @throws Exception when the store could not be cleared; the coordinator records it. */
    public suspend fun erase()
}

/** Asks the server to delete the account, its rows and its stored objects. */
public fun interface RemoteAccountEraser {
    /**
     * Requests deletion of the signed-in account.
     *
     * A device with no account signed in must answer [AccountDeletionReceipt.LOCAL_ONLY] rather
     * than a failure: there is nothing on the server to delete, and local data is still the user's
     * to erase.
     */
    public suspend fun deleteAccount(): DomainResult<AccountDeletionReceipt>
}

/**
 * What the server promised when it accepted the deletion.
 *
 * @property retentionWindowDays days until the last copy — backups included — is gone. Surfaced to
 *   the user because "deleted" and "unrecoverable" are different dates and the difference is the
 *   thing a privacy-conscious student actually asks about.
 */
public data class AccountDeletionReceipt(
    val acceptedAt: String?,
    val retentionWindowDays: Int,
) {
    public companion object {
        /** The documented window; see `docs/data-lifecycle.md`. */
        public const val DEFAULT_RETENTION_WINDOW_DAYS: Int = 30

        /** Nothing was signed in, so the server had nothing to delete. */
        public val LOCAL_ONLY: AccountDeletionReceipt = AccountDeletionReceipt(null, 0)
    }
}
