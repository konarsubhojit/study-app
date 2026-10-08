package dev.studyflow.core.storage.lifecycle

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("Material file store")
class FileMaterialFileStoreTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `stored bytes can be opened again from the returned path`() =
        runTest {
            val store = FileMaterialFileStore(File(root, "materials"))
            val bytes = "lecture notes".encodeToByteArray()

            val path = store.store("material-1", bytes.inputStream())
            val reopened = store.open(path)

            assertNotNull(reopened, "a path returned by store() must open")
            assertArrayEquals(bytes, reopened!!.use { it.readBytes() }, "the bytes must round-trip unchanged")
        }

    @Test
    fun `a path with no file behind it opens as null`() =
        runTest {
            val store = FileMaterialFileStore(File(root, "materials"))

            assertNull(store.open(File(root, "missing").absolutePath), "a missing file must read as absent")
        }
}
