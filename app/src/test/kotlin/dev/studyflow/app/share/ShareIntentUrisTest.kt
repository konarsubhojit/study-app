package dev.studyflow.app.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIntentUrisTest {
    @Test
    fun `a single SEND with EXTRA_STREAM yields one URI`() {
        val uri = "content://media/1".toUri()
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
            }

        assertEquals(listOf(uri), ShareIntentUris.extract(intent))
    }

    @Test
    fun `SEND_MULTIPLE with an EXTRA_STREAM array list yields every URI in order`() {
        val uris = arrayListOf("content://media/1".toUri(), "content://media/2".toUri())
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }

        assertEquals(uris, ShareIntentUris.extract(intent))
    }

    @Test
    fun `ClipData is preferred over EXTRA_STREAM when both are present`() {
        val clipUri = "content://media/from-clip".toUri()
        val extraUri = "content://media/from-extra".toUri()
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, extraUri)
                clipData = clipDataOf(clipUri)
            }

        assertEquals(listOf(clipUri), ShareIntentUris.extract(intent))
    }

    @Test
    fun `ClipData with multiple items yields every one of them`() {
        val first = "content://media/1".toUri()
        val second = "content://media/2".toUri()
        val clip =
            clipDataOf(first).apply {
                addItem(ClipData.Item(second))
            }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply { clipData = clip }

        assertEquals(listOf(first, second), ShareIntentUris.extract(intent))
    }

    @Test
    fun `a VIEW intent is not a share intent`() {
        val intent = Intent(Intent.ACTION_VIEW, "studyflow://materials".toUri())

        assertTrue(ShareIntentUris.extract(intent).isEmpty())
    }

    @Test
    fun `a SEND with no attachment at all yields nothing`() {
        val intent = Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_TEXT, "just words") }

        assertTrue(ShareIntentUris.extract(intent).isEmpty())
    }

    @Test
    fun `a null intent is handled without throwing`() {
        assertTrue(ShareIntentUris.extract(null).isEmpty())
    }

    private fun String.toUri(): Uri = Uri.parse(this)

    private fun clipDataOf(uri: Uri): ClipData = ClipData(ClipDescription("shared", arrayOf("*/*")), ClipData.Item(uri))
}
