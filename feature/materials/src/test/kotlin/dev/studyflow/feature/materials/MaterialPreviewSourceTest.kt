package dev.studyflow.feature.materials

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaterialPreviewSourceTest {
    @Test
    fun `local image preview keeps the imported file URI for Coil`() {
        val importedUri = "file:///data/user/0/studyflow/files/photo.png"

        val model = MaterialPreviewSource.Local(importedUri).previewModel()

        assertEquals(Uri.parse(importedUri), model)
    }

    @Test
    fun `local image preview converts a legacy path to a file URI for Coil`() {
        val path = "/data/user/0/studyflow/files/photo with spaces.png"

        val model = MaterialPreviewSource.Local(path).previewModel()

        assertEquals(Uri.fromFile(java.io.File(path)), model)
    }
}
