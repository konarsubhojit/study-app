package dev.studyflow.core.scheduling

import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.datastore.proto.SyncMode
import dev.studyflow.core.datastore.userSettingsStore
import dev.studyflow.core.domain.materials.UploadWaitReason
import dev.studyflow.core.domain.sync.SyncTrigger
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.logging.RecordingAppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkManagerMaterialUploadCoordinatorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val materialRepository = FakeMaterialRepository()
    private val requestedSyncs = mutableListOf<SyncTrigger>()
    private lateinit var workManager: WorkManager
    private lateinit var coordinator: WorkManagerMaterialUploadCoordinator
    private val logger = RecordingAppLogger()
    private val constraints =
        MutableStateFlow(UploadConstraints(connected = true, unmetered = true, batteryLow = false))
    private val constraintStatus = UploadConstraintStatus { constraints }

    @Before
    fun setUp() {
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
        coordinator =
            WorkManagerMaterialUploadCoordinator(
                context,
                materialRepository,
                context.userSettingsStore(),
                { trigger -> requestedSyncs += trigger },
                workManager,
                constraintStatus,
                logger,
            )
    }

    @Test
    fun `enqueueUpload enqueues exactly one unique work request for a pending material`() =
        runBlocking {
            materialRepository.save(material("m1", HASH_1))

            coordinator.enqueueUpload("m1")

            val infos = workManager.getWorkInfosForUniqueWork(materialUploadWorkName("m1")).get()
            assertEquals(1, infos.size)
            assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        }

    @Test
    fun `enqueueUpload is a no-op for a material that is already synced`() =
        runBlocking {
            materialRepository.save(
                material("m1", HASH_1).copy(sync = SyncState.Synced, remoteKey = "materials/$HASH_1"),
            )

            coordinator.enqueueUpload("m1")

            assertTrue(workManager.getWorkInfosForUniqueWork(materialUploadWorkName("m1")).get().isEmpty())
        }

    @Test
    fun `enqueueUpload short-circuits and marks synced when another material already stored the same content`() =
        runBlocking {
            materialRepository.save(
                material("existing", HASH_1).copy(sync = SyncState.Synced, remoteKey = "materials/$HASH_1"),
            )
            materialRepository.save(material("duplicate", HASH_1))

            coordinator.enqueueUpload("duplicate")

            assertTrue(
                "a material whose bytes are already stored elsewhere must never be queued for upload",
                workManager.getWorkInfosForUniqueWork(materialUploadWorkName("duplicate")).get().isEmpty(),
            )
            val duplicate = materialRepository.observeById("duplicate").first()
            assertEquals(SyncState.Synced, duplicate?.sync)
            assertEquals("materials/$HASH_1", duplicate?.remoteKey)
            assertEquals(listOf(SyncTrigger.OUTBOUND), requestedSyncs)
        }

    @Test
    fun `a second enqueueUpload for the same material does not duplicate the work`() =
        runBlocking {
            materialRepository.save(material("m1", HASH_1))

            coordinator.enqueueUpload("m1")
            coordinator.enqueueUpload("m1")

            assertEquals(1, workManager.getWorkInfosForUniqueWork(materialUploadWorkName("m1")).get().size)
        }

    @Test
    fun `cancelUpload cancels the unique work for that material`() =
        runBlocking {
            materialRepository.save(material("m1", HASH_1))
            coordinator.enqueueUpload("m1")

            coordinator.cancelUpload("m1")

            val info = workManager.getWorkInfosForUniqueWork(materialUploadWorkName("m1")).get().single()
            assertEquals(WorkInfo.State.CANCELLED, info.state)
        }

    @Test
    fun `enqueueUpload records the constraint the upload now has to satisfy`() =
        runBlocking {
            materialRepository.save(material(MATERIAL_UUID, HASH_1))

            coordinator.enqueueUpload(MATERIAL_UUID)

            // Without this line an upload held on an unmet constraint produces no log at all, and
            // an export cannot tell "never enqueued" from "enqueued and waiting".
            assertEquals(
                listOf(
                    "code=MaterialUploadEnqueued syncMode=SYNC_MODE_WIFI_ONLY networkType=UNMETERED " +
                        "materialId=$MATERIAL_UUID",
                ),
                logger.diagnosticsWith(DiagnosticCode.MaterialUploadEnqueued),
            )
        }

    @Test
    fun `a queued upload on mobile data reports that it is waiting for Wi-Fi`() =
        runBlocking {
            materialRepository.save(material("m1", HASH_1))
            coordinator.enqueueUpload("m1")
            constraints.value = UploadConstraints(connected = true, unmetered = false, batteryLow = false)

            assertEquals(UploadWaitReason.WAITING_FOR_WIFI, coordinator.waitReason.first())
        }

    @Test
    fun `nothing is reported as waiting when the queue is empty`() =
        runBlocking {
            constraints.value = UploadConstraints(connected = false, unmetered = false, batteryLow = false)

            assertEquals(null, coordinator.waitReason.first())
        }

    @Test
    fun `the Wi-Fi-only default is the only mode that demands an unmetered network`() {
        // Stated rather than assumed: every other mode uploads on whatever connection exists, so
        // this is the one setting that can park a material indefinitely.
        assertEquals(
            NetworkType.UNMETERED,
            WorkManagerMaterialUploadCoordinator.networkTypeFor(SyncMode.SYNC_MODE_WIFI_ONLY),
        )
        assertEquals(
            NetworkType.CONNECTED,
            WorkManagerMaterialUploadCoordinator.networkTypeFor(SyncMode.SYNC_MODE_ANY_NETWORK),
        )
    }

    private fun material(
        id: String,
        hash: String,
    ): Material =
        Material(
            id = id,
            displayName = "lecture.mp4",
            mimeType = "video/mp4",
            sizeBytes = 1024L,
            contentHash = ContentHash(hash),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            localPath = "/tmp/$id.mp4",
        )

    private companion object {
        const val MATERIAL_UUID = "f058ccde-0000-4000-8000-000000000001"
        const val HASH_1 = "b000000000000000000000000000000000000000000000000000000000000001"
    }
}
