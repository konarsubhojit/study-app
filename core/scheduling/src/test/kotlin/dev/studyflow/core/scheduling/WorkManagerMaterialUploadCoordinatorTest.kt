package dev.studyflow.core.scheduling

import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.datastore.userSettingsStore
import dev.studyflow.core.domain.materials.UploadWaitReason
import dev.studyflow.core.domain.sync.SyncTrigger
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.logging.RecordingAppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private val logger = RecordingAppLogger()
    private val conditions =
        MutableStateFlow(UploadConditions(connected = true, metered = true, batteryLow = false))
    private lateinit var workManager: WorkManager
    private lateinit var coordinator: WorkManagerMaterialUploadCoordinator

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
                logger,
                conditions,
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
    fun `every enqueue is logged with the constraint it was given, before any constraint can hold it`() =
        runBlocking {
            materialRepository.save(material(UUID_1, HASH_1))

            coordinator.enqueueUpload(UUID_1)

            val event = logger.diagnosticsWith(DiagnosticCode.MaterialUpload).single()
            assertTrue(event, "stage=ENQUEUE outcome=QUEUED materialId=$UUID_1" in event)
            assertTrue(event, "networkType=UNMETERED syncMode=SYNC_MODE_WIFI_ONLY" in event)
        }

    @Test
    fun `an upload held by the Wi-Fi-only constraint reports that it is waiting for Wi-Fi`() =
        runBlocking {
            materialRepository.save(material(UUID_1, HASH_1))
            coordinator.enqueueUpload(UUID_1)

            val reason = withTimeout(TIMEOUT_MILLIS) { coordinator.waitReason.first { it != null } }

            assertEquals(UploadWaitReason.WAITING_FOR_WIFI, reason)
            assertEquals(
                "the request is enqueued, not running and not failed",
                WorkInfo.State.ENQUEUED,
                workManager.getWorkInfosForUniqueWork(materialUploadWorkName(UUID_1)).get().single().state,
            )

            conditions.value = conditions.value.copy(metered = false)
            assertEquals(null, withTimeout(TIMEOUT_MILLIS) { coordinator.waitReason.first { it == null } })
        }

    @Test
    fun `allowing mobile data re-applies the constraint to uploads already waiting`() =
        runBlocking {
            val settings = WorkManagerUploadNetworkSettings(context.userSettingsStore(), coordinator)
            materialRepository.save(material("m1", HASH_1))
            coordinator.enqueueUpload("m1")
            try {
                settings.setWifiOnly(false)

                val info = workManager.getWorkInfosForUniqueWork(materialUploadWorkName("m1")).get().single()
                assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
                assertEquals(WorkInfo.State.ENQUEUED, info.state)
            } finally {
                settings.setWifiOnly(true)
            }
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
        const val UUID_1 = "00000000-0000-0000-0000-0000000000c1"
        const val TIMEOUT_MILLIS = 5_000L
        const val HASH_1 = "b000000000000000000000000000000000000000000000000000000000000001"
    }
}
