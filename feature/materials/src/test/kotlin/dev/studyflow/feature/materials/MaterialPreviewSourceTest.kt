package dev.studyflow.feature.materials

import android.net.Uri
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaterialPreviewSourceTest {
    @Test
    fun `local image preview keeps the imported file URI for Coil`() {
        val importedUri = Uri.fromFile(File("photo.png")).toString()

        val model = MaterialPreviewSource.Local(importedUri).previewModel()

        assertEquals(importedUri.toUri(), model)
    }

    @Test
    fun `local image preview converts a legacy path to a file URI for Coil`() {
        val image = File("photo with spaces.png")
        val path = image.absolutePath

        val model = MaterialPreviewSource.Local(path).previewModel()

        assertEquals(Uri.fromFile(image), model)
    }
}
