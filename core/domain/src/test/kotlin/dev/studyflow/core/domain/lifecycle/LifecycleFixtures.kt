package dev.studyflow.core.domain.lifecycle

import dev.studyflow.core.model.Material
import dev.studyflow.core.model.StudyTask
import dev.studyflow.core.model.Subject
import dev.studyflow.core.testing.data.TEST_DEVICE_ID
import dev.studyflow.core.testing.data.TEST_WALL_CLOCK

/**
 * Builds an archive document straight from models.
 *
 * Tests about the *format* should not have to go through an exporter, and tests about the
 * *exporter* should not have to hand-write JSON; this is the seam between the two.
 */
internal fun archiveOf(
    subjects: List<Subject> = emptyList(),
    sessions: List<SessionWithLog> = emptyList(),
    tasks: List<StudyTask> = emptyList(),
    materials: List<Pair<Material, String?>> = emptyList(),
): DataArchive =
    DataArchive(
        exportedAt = TEST_WALL_CLOCK.toString(),
        deviceId = TEST_DEVICE_ID,
        subjects = subjects.map { it.toArchived() },
        sessions = sessions.map { it.toArchived() },
        tasks = tasks.map { it.toArchived() },
        materials = materials.map { (material, entry) -> material.toArchived(entry) },
    )
