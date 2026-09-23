package dev.studyflow.feature.materials

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.studyflow.core.testing.data.testMaterial
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaterialExportTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `export copies a local material into a Downloads media row`() =
        runTest {
            val source = tempDir.newFile("notes.pdf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val material =
                testMaterial(
                    id = "material-1",
                    displayName = "notes.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = source.length(),
                )
            val uri = Uri.parse("content://downloads/1")
            val copied = ByteArrayOutputStream()
            lateinit var insertedValues: ContentValues
            var finishedUri: Uri? = null

            val exported =
                exportLocalMaterialToDownloads(
                    context = RuntimeEnvironment.getApplication(),
                    material = material,
                    source = MaterialPreviewSource.Local(source.absolutePath),
                    insertDownload = { values ->
                        insertedValues = ContentValues(values)
                        uri
                    },
                    openOutput = { copied },
                    markFinished = { finishedUri = it },
                )

            assertEquals(MaterialExportResult.Exported, exported)
            assertEquals("notes.pdf", insertedValues.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
            assertEquals("application/pdf", insertedValues.getAsString(MediaStore.MediaColumns.MIME_TYPE))
            assertEquals(Environment.DIRECTORY_DOWNLOADS, insertedValues.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
            assertEquals(1, insertedValues.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
            assertArrayEquals(source.readBytes(), copied.toByteArray())
            assertEquals(uri, finishedUri)
        }

    @Test
    fun `export deletes the inserted Downloads row when copying fails`() =
        runTest {
            val source = tempDir.newFile("notes.txt").apply { writeText("hello") }
            val material = testMaterial(id = "material-1", displayName = "notes.txt")
            val uri = Uri.parse("content://downloads/2")
            var deletedUri: Uri? = null

            val exported =
                exportLocalMaterialToDownloads(
                    context = RuntimeEnvironment.getApplication(),
                    material = material,
                    source = MaterialPreviewSource.Local(source.absolutePath),
                    insertDownload = { uri },
                    openOutput = { FailingOutputStream },
                    deleteDownload = { deletedUri = it },
                )

            assertEquals(MaterialExportResult.Failed, exported)
            assertEquals(uri, deletedUri)
        }

    @Test
    fun `export deletes the inserted Downloads row when the provider cannot open output`() =
        runTest {
            val source = tempDir.newFile("notes.txt").apply { writeText("hello") }
            val material = testMaterial(id = "material-1", displayName = "notes.txt")
            val uri = Uri.parse("content://downloads/3")
            var deletedUri: Uri? = null

            val exported =
                exportLocalMaterialToDownloads(
                    context = RuntimeEnvironment.getApplication(),
                    material = material,
                    source = MaterialPreviewSource.Local(source.absolutePath),
                    insertDownload = { uri },
                    openOutput = { null },
                    deleteDownload = { deletedUri = it },
                )

            assertEquals(MaterialExportResult.Failed, exported)
            assertEquals(uri, deletedUri)
        }

    @Test
    fun `export deletes the inserted Downloads row when marking complete fails`() =
        runTest {
            val source = tempDir.newFile("notes.txt").apply { writeText("hello") }
            val material = testMaterial(id = "material-1", displayName = "notes.txt")
            val uri = Uri.parse("content://downloads/7")
            var deletedUri: Uri? = null

            val exported =
                exportLocalMaterialToDownloads(
                    context = RuntimeEnvironment.getApplication(),
                    material = material,
                    source = MaterialPreviewSource.Local(source.absolutePath),
                    insertDownload = { uri },
                    openOutput = { ByteArrayOutputStream() },
                    markFinished = { throw SecurityException("update denied") },
                    deleteDownload = { deletedUri = it },
                )

            assertEquals(MaterialExportResult.Failed, exported)
            assertEquals(uri, deletedUri)
        }

    @Test
    @Config(sdk = [28])
    fun `export reports unsupported before Android 10`() =
        runTest {
            val source = tempDir.newFile("notes.txt").apply { writeText("hello") }

            val exported =
                exportLocalMaterialToDownloads(
                    context = RuntimeEnvironment.getApplication(),
                    material = testMaterial(id = "material-1", displayName = "notes.txt"),
                    source = MaterialPreviewSource.Local(source.absolutePath),
                )

            assertEquals(MaterialExportResult.Unsupported, exported)
        }

    @Test
    fun `export fails before inserting when source is remote`() =
        runTest {
            var insertCalled = false

            val exported =
                exportLocalMaterialToDownloads(
                    context = RuntimeEnvironment.getApplication(),
                    material = testMaterial(id = "material-1", displayName = "notes.txt"),
                    source = MaterialPreviewSource.Remote("content://materials/notes.txt"),
                    insertDownload = {
                        insertCalled = true
                        Uri.parse("content://downloads/4")
                    },
                )

            assertEquals(MaterialExportResult.Failed, exported)
            assertEquals(false, insertCalled)
        }

    @Test
    fun `export fails before inserting when local source file is missing`() =
        runTest {
            var insertCalled = false

            val exported =
                exportLocalMaterialToDownloads(
                    context = RuntimeEnvironment.getApplication(),
                    material = testMaterial(id = "material-1", displayName = "notes.txt"),
                    source = MaterialPreviewSource.Local(File(tempDir.root, "missing.txt").absolutePath),
                    insertDownload = {
                        insertCalled = true
                        Uri.parse("content://downloads/5")
                    },
                )

            assertEquals(MaterialExportResult.Failed, exported)
            assertEquals(false, insertCalled)
        }

    @Test
    fun `export fails before inserting when local source is not a file URI`() =
        runTest {
            var insertCalled = false

            val exported =
                exportLocalMaterialToDownloads(
                    context = RuntimeEnvironment.getApplication(),
                    material = testMaterial(id = "material-1", displayName = "notes.txt"),
                    source = MaterialPreviewSource.Local("content://materials/notes.txt"),
                    insertDownload = {
                        insertCalled = true
                        Uri.parse("content://downloads/6")
                    },
                )

            assertEquals(MaterialExportResult.Failed, exported)
            assertEquals(false, insertCalled)
        }

    @Test
    fun `export fails when provider cannot insert a Downloads row`() =
        runTest {
            val source = tempDir.newFile("notes.txt").apply { writeText("hello") }

            val exported =
                exportLocalMaterialToDownloads(
                    context = RuntimeEnvironment.getApplication(),
                    material = testMaterial(id = "material-1", displayName = "notes.txt"),
                    source = MaterialPreviewSource.Local(source.absolutePath),
                    insertDownload = { null },
                )

            assertEquals(MaterialExportResult.Failed, exported)
        }

    @Test
    fun `export fails when provider rejects insert`() =
        runTest {
            val source = tempDir.newFile("notes.txt").apply { writeText("hello") }

            val exported =
                exportLocalMaterialToDownloads(
                    context = RuntimeEnvironment.getApplication(),
                    material = testMaterial(id = "material-1", displayName = "notes.txt"),
                    source = MaterialPreviewSource.Local(source.absolutePath),
                    insertDownload = { throw SecurityException("denied") },
                )

            assertEquals(MaterialExportResult.Failed, exported)
        }

    @Test
    fun `export fails when provider reports invalid insert metadata`() =
        runTest {
            val source = tempDir.newFile("notes.txt").apply { writeText("hello") }

            val exported =
                exportLocalMaterialToDownloads(
                    context = RuntimeEnvironment.getApplication(),
                    material = testMaterial(id = "material-1", displayName = "notes.txt"),
                    source = MaterialPreviewSource.Local(source.absolutePath),
                    insertDownload = { throw IllegalArgumentException("invalid metadata") },
                )

            assertEquals(MaterialExportResult.Failed, exported)
        }

    private object FailingOutputStream : OutputStream() {
        override fun write(b: Int) {
            throw IOException("disk full")
        }
    }
}
