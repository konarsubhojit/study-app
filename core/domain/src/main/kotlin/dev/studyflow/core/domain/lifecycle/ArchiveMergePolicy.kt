package dev.studyflow.core.domain.lifecycle

import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SessionStatus
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subject
import kotlin.time.Instant

/** What an import decided to do with one archived record. */
public enum class MergeDecision {
    /** No local counterpart existed. */
    INSERT,

    /** A local counterpart existed and the archived copy is newer. */
    UPDATE,

    /** A local counterpart existed and is at least as new, so it is left alone. */
    SKIP,
}

/** The records of one kind an import will write, plus how many it left alone. */
public data class MergeOutcome<T>(
    val inserted: List<T> = emptyList(),
    val updated: List<T> = emptyList(),
    val skipped: Int = 0,
) {
    public val writes: List<T> get() = inserted + updated
}

/** One archived material and what should happen to it, resolved before any byte is restored. */
public data class MaterialMergeAction(
    val record: ArchivedMaterial,
    val decision: MergeDecision,
    val localPath: String?,
)

/**
 * The rules that make a re-import boring (issue #78).
 *
 * Two properties matter more than anything clever:
 *
 * 1. **Importing the same archive twice changes nothing the second time.** Records carry stable
 *    ids, so the second pass finds a local counterpart with an identical `updatedAt` and skips it.
 *    Materials additionally deduplicate on their content hash, because the same file imported on
 *    two devices legitimately has two ids and only one set of bytes.
 * 2. **An import never silently loses newer local work.** The device the archive is restored onto
 *    may have been used since the export was taken; last-write-wins on `updatedAt` — the same rule
 *    the sync protocol uses (ADR 0012) — keeps the newer edit, and a tie keeps the local copy
 *    because rewriting a row with an identical one is pure risk for no gain.
 *
 * Subjects are the exception: they carry no `updatedAt`, so an existing subject is never
 * overwritten. Losing a rename on restore is a nuisance; overwriting a rename the user made after
 * the export is data loss.
 */
public object ArchiveMergePolicy {
    /** The core comparison, shared by every record type that has an `updatedAt`. */
    public fun decide(
        importedUpdatedAt: Instant,
        localUpdatedAt: Instant?,
    ): MergeDecision =
        when {
            localUpdatedAt == null -> MergeDecision.INSERT
            importedUpdatedAt > localUpdatedAt -> MergeDecision.UPDATE
            else -> MergeDecision.SKIP
        }

    public fun mergeSubjects(
        imported: List<ArchivedSubject>,
        local: List<Subject>,
    ): MergeOutcome<Subject> {
        val known = local.mapTo(mutableSetOf(), Subject::id)
        val inserted = mutableListOf<Subject>()
        var skipped = 0
        imported.forEach { record ->
            if (known.add(record.id)) inserted += record.toModel() else skipped++
        }
        return MergeOutcome(inserted = inserted, skipped = skipped)
    }

    /**
     * Resolves sessions, re-homing any that were still running when the export was taken.
     *
     * A stopwatch belongs to the device it runs on — the same reason sync refuses to replicate an
     * active session — so a restored session is always closed. Leaving it open would give the new
     * device a second active session and a counted interval anchored to a boot that no longer
     * exists.
     */
    public fun mergeSessions(
        imported: List<ArchivedSession>,
        local: List<SessionWithLog>,
    ): MergeOutcome<SessionWithLog> {
        val localById = local.associateBy { it.session.id }
        val inserted = mutableListOf<SessionWithLog>()
        val updated = mutableListOf<SessionWithLog>()
        var skipped = 0

        imported.forEach { record ->
            when (decide(record.updatedAt.parsedUpdatedAt(record.id), localById[record.id]?.session?.updatedAt)) {
                MergeDecision.INSERT -> inserted += record.toModel().closed()
                MergeDecision.UPDATE -> updated += record.toModel().closed()
                MergeDecision.SKIP -> skipped++
            }
        }
        return MergeOutcome(inserted = inserted, updated = updated, skipped = skipped)
    }

    public fun mergeTasks(
        imported: List<ArchivedTask>,
        local: List<StudyTask>,
    ): MergeOutcome<StudyTask> {
        val localById = local.associateBy(StudyTask::id)
        val inserted = mutableListOf<StudyTask>()
        val updated = mutableListOf<StudyTask>()
        var skipped = 0

        imported.forEach { record ->
            when (decide(record.updatedAt.parsedUpdatedAt(record.id), localById[record.id]?.updatedAt)) {
                MergeDecision.INSERT -> inserted += record.toModel()
                MergeDecision.UPDATE -> updated += record.toModel()
                MergeDecision.SKIP -> skipped++
            }
        }
        return MergeOutcome(inserted = inserted, updated = updated, skipped = skipped)
    }

    /**
     * Resolves materials by id first and by content hash second.
     *
     * The content-hash fallback is what stops the same lecture PDF arriving twice when it was
     * imported separately on two devices: the bytes are identical by definition, so a second
     * catalogue row would cost storage and clutter the catalogue for nothing.
     *
     * @return one action per archived record, carrying the local path of an existing copy so the
     *   caller knows which files it still has to restore.
     */
    public fun planMaterials(
        imported: List<ArchivedMaterial>,
        local: List<Material>,
    ): List<MaterialMergeAction> {
        val localById = local.associateBy(Material::id)
        val localByHash = local.associateBy { it.contentHash.hex }

        return imported.map { record ->
            val existing = localById[record.id]
            val decision =
                when {
                    existing != null -> decide(record.updatedAt.parsedUpdatedAt(record.id), existing.updatedAt)
                    localByHash.containsKey(record.contentHash) -> MergeDecision.SKIP
                    else -> MergeDecision.INSERT
                }
            MaterialMergeAction(
                record = record,
                decision = decision,
                localPath = (existing ?: localByHash[record.contentHash])?.localPath,
            )
        }
    }

    /** Parses a record's `updatedAt`, naming the record when an archive was hand-edited. */
    private fun String.parsedUpdatedAt(recordId: String): Instant =
        try {
            Instant.parse(this)
        } catch (invalid: IllegalArgumentException) {
            throw ArchiveFormatException("record '$recordId' has an unreadable updatedAt '$this'", invalid)
        }

    /**
     * Closes a restored session.
     *
     * [StudySession.manualOverride] is set because the resulting projection is a decision about
     * what happened rather than something a reader could re-derive by replaying the log: the log
     * still ends with an open interval from a boot on another device.
     */
    private fun SessionWithLog.closed(): SessionWithLog {
        if (session.status == SessionStatus.STOPPED) return this
        val endedAt = session.endedAt ?: events.maxByOrNull { it.sequence }?.anchor?.wallClock ?: session.startedAt
        return copy(
            session =
                session.copy(
                    status = SessionStatus.STOPPED,
                    endedAt = endedAt,
                    manualOverride = true,
                ),
        )
    }
}
