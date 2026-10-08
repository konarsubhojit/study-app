package dev.studyflow.core.scheduling

import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkManagerMaterialDownloadCoordinatorTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `a download waits for a network, a battery that is not low and free storage`() =
        runBlocking {
            WorkManagerMaterialDownloadCoordinator(context, workManager).enqueueDownload("m1")

            val info = workManager.getWorkInfosForUniqueWork(materialDownloadWorkName("m1")).get().single()
            val constraints = info.constraints
            assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
            assertTrue("a large download should not drain a low battery", constraints.requiresBatteryNotLow())
            assertTrue("a large download should not fill a nearly full device", constraints.requiresStorageNotLow())
        }
}
