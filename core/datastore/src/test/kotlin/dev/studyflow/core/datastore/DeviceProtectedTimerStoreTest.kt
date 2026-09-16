package dev.studyflow.core.datastore

import android.content.Context
import android.content.ContextWrapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DeviceProtectedTimerStoreTest {
    @Test
    fun `active timer anchor file is in device protected storage`() {
        val deviceProtectedContext = TestContext(isDeviceProtected = true)
        val context = TestContext(deviceProtectedContext)

        val file: File = deviceProtectedTimerFile(context)

        assertTrue(deviceProtectedContext.isDeviceProtectedStorage)
        assertTrue(file.path.startsWith(deviceProtectedContext.filesDir.path))
    }

    private class TestContext(
        private val deviceProtectedContext: TestContext? = null,
        private val isDeviceProtected: Boolean = false,
    ) : ContextWrapper(null) {
        private val filesDirectory = createTempDirectory().toFile()

        override fun createDeviceProtectedStorageContext(): Context = deviceProtectedContext ?: this

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = filesDirectory

        override fun isDeviceProtectedStorage(): Boolean = isDeviceProtected
    }
}
