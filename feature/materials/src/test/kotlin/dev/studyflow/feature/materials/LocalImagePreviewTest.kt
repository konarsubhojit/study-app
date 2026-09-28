package dev.studyflow.feature.materials

import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class LocalImagePreviewTest {
    @Test
    fun `imported image decodes through coil`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val file = File(context.cacheDir, "photo.jpg")
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", file)

            val source = MaterialPreviewSource.Local(file.toURI().toString())
            val result =
                ImageLoader(context).execute(
                    ImageRequest.Builder(context).data(source.previewModel()).build(),
                )

            assertTrue("coil loaded the imported image, got $result", result is SuccessResult)
        }
}
