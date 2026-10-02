package dev.studyflow.core.testing.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins the fake to what `OfflineFirstMaterialRepository` does over `MaterialDao`: a delete is a
 * soft delete whose tombstone the observation queries (`deleted = 0`) never surface. A fake that
 * kept emitting the deleted row would hide every bug in the "material just disappeared" window.
 */
@DisplayName("FakeMaterialRepository")
class FakeMaterialRepositoryTest {
    private val repository = FakeMaterialRepository()

    @Test
    fun `deleting a material makes observeById emit null like the dao`() =
        runTest {
            repository.save(testMaterial(id = "material-1"))
            val observed = repository.observeById("material-1")
            assertEquals("material-1", observed.first()?.id)

            repository.delete("material-1")

            assertNull(observed.first(), "a soft-deleted material is gone from observeById")
        }

    @Test
    fun `a deleted material leaves the catalogue and its folder`() =
        runTest {
            repository.save(testMaterial(id = "kept", folderId = "folder", contentHash = testContentHash("kept")))
            repository.save(testMaterial(id = "removed", folderId = "folder", contentHash = testContentHash("removed")))

            repository.delete("removed")

            assertEquals(listOf("kept"), repository.observeAll().first().map { it.id })
            assertEquals(listOf("kept"), repository.observeInFolder("folder").first().map { it.id })
        }

    @Test
    fun `the tombstone is kept for sync rather than dropped`() =
        runTest {
            val hash = testContentHash("removed")
            repository.save(testMaterial(id = "removed", contentHash = hash))

            repository.delete("removed")

            // Like `MaterialDao.findByContentHash`, which does not filter `deleted`.
            val tombstone = repository.findByContentHash(hash)
            assertEquals("removed", tombstone?.id)
            assertTrue(tombstone?.deleted == true, "the row is marked deleted, not removed")
        }
}
