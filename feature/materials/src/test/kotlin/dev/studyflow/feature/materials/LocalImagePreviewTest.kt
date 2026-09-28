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

/**
 * Covers an imported image end to end: the value stored in `Material.localPath` has to survive
 * [previewModel] as something Coil can fetch *and* decode, which is what the blank preview of
 * issue #164 failed to do.
 *
 * Pinned to API 27 because Robolectric cannot run the `ImageDecoder` that Coil picks from API 28
 * up; the fetcher under test is the same one on every API level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class LocalImagePreviewTest {
    @Test
    fun `an imported file URI decodes through coil`() = assertDecodes { it.toURI().toString() }

    @Test
    fun `a legacy absolute path decodes through coil`() = assertDecodes(File::getAbsolutePath)

    private fun assertDecodes(localPath: (File) -> String) =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val image = File(context.cacheDir, "photo.jpg")
            ImageIO.write(BufferedImage(IMAGE_PIXELS, IMAGE_PIXELS, BufferedImage.TYPE_INT_RGB), "jpg", image)
            val source = MaterialPreviewSource.Local(localPath(image))

            val result =
                ImageLoader(context).execute(
                    ImageRequest.Builder(context).data(source.previewModel()).build(),
                )

            assertTrue("coil rendered the imported image but got $result", result is SuccessResult)
        }

    private companion object {
        const val IMAGE_PIXELS = 8
    }
}
