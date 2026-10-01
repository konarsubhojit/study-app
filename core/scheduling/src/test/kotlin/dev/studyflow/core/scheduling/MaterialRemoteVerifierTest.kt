package dev.studyflow.core.scheduling

import dev.studyflow.core.common.logging.DiagnosticCode
import dev.studyflow.core.model.ContentHash
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.SyncState
import dev.studyflow.core.storage.ObjectKey
import dev.studyflow.core.storage.ObjectStoreException
import dev.studyflow.core.testing.data.FakeMaterialRepository
import dev.studyflow.core.testing.data.FakeMaterialUploadCoordinator
import dev.studyflow.core.testing.logging.RecordingAppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@DisplayName("MaterialRemoteVerifier")
class MaterialRemoteVerifierTest {
    private val materialRepository = FakeMaterialRepository()
    private val objectStore = RecordingObjectStore()
    private val uploadCoordinator = FakeMaterialUploadCoordinator()
    private val ledger = InMemoryLedger()
    private val logger = RecordingAppLogger()
    private val verifier =
        MaterialRemoteVerifier(materialRepository, objectStore, uploadCoordinator, ledger, logger)

    @Test
    fun `a synced material whose remote object is missing goes back to pending and is re-uploaded`() =
        runBlocking {
            materialRepository.save(synced(ID_1, HASH_1, localPath = "/files/notes.pdf"))

            assertEquals(1, verifier.verify())

            val healed = materialRepository.observeById(ID_1).first()
            assertEquals(SyncState.Pending, healed?.sync)
            assertNull(healed?.remoteKey, "a key that resolves to nothing must not survive the heal")
            assertEquals(listOf(ID_1), uploadCoordinator.enqueued)
            assertTrue(
                logger.diagnosticsWith(DiagnosticCode.MaterialUpload).any {
                    "stage=DEDUPE outcome=REMOTE_MISSING retryable=true materialId=$ID_1" in it
                },
            )
        }

    @Test
    fun `a missing remote object with no local file becomes re-attach or remove, never retry`() =
        runBlocking {
            materialRepository.save(synced(ID_1, HASH_1, localPath = null))

            verifier.verify()

            val healed = materialRepository.observeById(ID_1).first()!!
            val sync = healed.sync
            assertTrue(sync is SyncState.Failed && !sync.retryable, "nothing on this device can re-upload it")
            assertTrue(healed.isMissingSource)
            assertTrue(uploadCoordinator.enqueued.isEmpty())
        }

    @Test
    fun `a stored object is confirmed once and not asked about again`() =
        runBlocking {
            materialRepository.save(synced(ID_1, HASH_1, localPath = "/files/notes.pdf"))
            objectStore.seedStoredObject(ObjectKey.ofMaterial(ContentHash(HASH_1)), SIZE, ContentHash(HASH_1))

            assertEquals(0, verifier.verify())
            assertEquals(0, verifier.verify())

            assertEquals(SyncState.Synced, materialRepository.observeById(ID_1).first()?.sync)
            assertEquals(1, objectStore.statted.size, "the ledger spares the storage function's rate limit")
        }

    @Test
    fun `a transient failure is never mistaken for a missing object`() =
        runBlocking {
            materialRepository.save(synced(ID_1, HASH_1, localPath = "/files/notes.pdf"))
            objectStore.failStat = ObjectStoreException.Transient("offline")

            assertEquals(0, verifier.verify())

            assertEquals(SyncState.Synced, materialRepository.observeById(ID_1).first()?.sync)
            assertTrue(uploadCoordinator.enqueued.isEmpty())
        }

    @Test
    fun `twins sharing a missing key are healed together so neither re-adopts the broken key`() =
        runBlocking {
            materialRepository.save(synced(ID_1, HASH_1, localPath = "/files/a.pdf"))
            materialRepository.save(synced(ID_2, HASH_1, localPath = "/files/b.pdf"))

            assertEquals(2, verifier.verify())

            assertEquals(SyncState.Pending, materialRepository.observeById(ID_1).first()?.sync)
            assertEquals(SyncState.Pending, materialRepository.observeById(ID_2).first()?.sync)
            assertEquals(1, objectStore.statted.size)
        }

    @Test
    fun `a download that finds nothing heals the material too`() =
        runBlocking {
            materialRepository.save(synced(ID_1, HASH_1, localPath = null))

            verifier.onRemoteMissing(ID_1)

            assertTrue(materialRepository.observeById(ID_1).first()!!.isMissingSource)
        }

    private fun synced(
        id: String,
        hash: String,
        localPath: String?,
    ): Material =
        Material(
            id = id,
            displayName = "notes.pdf",
            mimeType = "application/pdf",
            sizeBytes = SIZE,
            contentHash = ContentHash(hash),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            localPath = localPath,
            remoteKey = "materials/$hash",
            sync = SyncState.Synced,
        )

    private class InMemoryLedger : RemoteCopyLedger {
        private val verified = mutableMapOf<String, String>()

        override fun isVerified(
            materialId: String,
            remoteKey: String,
        ): Boolean = verified[materialId] == remoteKey

        override fun markVerified(
            materialId: String,
            remoteKey: String,
        ) {
            verified[materialId] = remoteKey
        }
    }

    private companion object {
        const val ID_1 = "00000000-0000-0000-0000-00000000000a"
        const val ID_2 = "00000000-0000-0000-0000-00000000000b"
        val HASH_1 = "c".repeat(64)
        const val SIZE = 1024L
    }
}
