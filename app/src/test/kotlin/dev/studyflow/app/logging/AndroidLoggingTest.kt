package dev.studyflow.app.logging

import dev.studyflow.core.common.logging.LogSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import timber.log.Timber

/**
 * The privacy promise of the release logger, checked against a real `android.util.Log`.
 *
 * This is what Robolectric is for in this project: the behaviour under test is entirely about what
 * reaches the platform log, so stubbing `Log` out would test nothing. Robolectric supplies a real
 * implementation and a readable record of what was written, in the fast JVM suite and without an
 * emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class AndroidLoggingTest {
    @Test
    fun `a release build logs no user content`() {
        AndroidLogging.install(debug = false)

        Timber.tag("materials").e(IllegalStateException("boom"), "upload of /storage/emulated/0/exam.pdf failed")

        val entry = ShadowLog.getLogs().single { it.tag == LogSanitizer.RELEASE_TAG }
        assertEquals("level=Error throwable=IllegalStateException", entry.msg)
        assertFalse(entry.msg.contains("exam.pdf"))
        assertFalse(entry.msg.contains("storage"))
    }
}

/** Pinned so the suite does not change behaviour the next time `compileSdk` moves. */
internal const val ROBOLECTRIC_SDK = 34
