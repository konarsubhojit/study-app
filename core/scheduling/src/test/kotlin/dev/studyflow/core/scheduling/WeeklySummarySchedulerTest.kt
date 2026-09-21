package dev.studyflow.core.scheduling

import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.studyflow.core.common.time.Clock
import dev.studyflow.core.common.time.TimeZoneProvider
import dev.studyflow.core.datastore.WeeklySummarySchedule
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklySummarySchedulerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val settings = FakeWeeklySummarySettings()
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WeeklySummaryScheduler

    @Before
    fun setUp() {
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
        scheduler =
            WeeklySummaryScheduler(
                settings = settings,
                workManager = workManager,
                clock = Clock { NOW },
                timeZoneProvider = TimeZoneProvider { TimeZone.UTC },
            )
    }

    @Test
    fun `an opted-in user gets one weekly request, anchored to the chosen day and time`() =
        runBlocking {
            // Sunday at 18:00 UTC, from Wednesday 2026-03-04T09:00Z: four days and nine hours out.
            settings.set(WeeklySummarySchedule(enabled = true, isoDayOfWeek = 7, hour = 18, minute = 0))

            scheduler.sync()

            val info = enqueued()
            assertEquals(WorkInfo.State.ENQUEUED, info.state)
            assertEquals(
                TimeUnit.DAYS.toMillis(7),
                info.periodicityInfo
                    ?.repeatIntervalMillis,
            )
            assertEquals(
                "the first run lands on the chosen day and time",
                TimeUnit.HOURS.toMillis(4 * 24 + 9),
                info.initialDelayMillis,
            )
        }

    @Test
    fun `choosing a new day re-anchors the existing request instead of waiting out the old one`() =
        runBlocking {
            settings.set(WeeklySummarySchedule(enabled = true, isoDayOfWeek = 7, hour = 18, minute = 0))
            scheduler.sync()

            settings.set(WeeklySummarySchedule(enabled = true, isoDayOfWeek = 4, hour = 8, minute = 30))
            scheduler.sync()

            val info = enqueued()
            assertEquals(
                "Thursday 08:30 is 23.5 hours after Wednesday 09:00",
                TimeUnit.MINUTES.toMillis(23 * 60 + 30),
                info.initialDelayMillis,
            )
        }

    @Test
    fun `opting out cancels the work rather than leaving it to no-op forever`() =
        runBlocking {
            settings.set(WeeklySummarySchedule(enabled = true, isoDayOfWeek = 7, hour = 18, minute = 0))
            scheduler.sync()

            settings.set(WeeklySummarySchedule(enabled = false))
            scheduler.sync()

            val infos = workManager.getWorkInfosForUniqueWork(WEEKLY_SUMMARY_WORK_NAME).get()
            assertTrue(infos.all { it.state == WorkInfo.State.CANCELLED })
        }

    @Test
    fun `a never-enabled summary enqueues nothing at all`() =
        runBlocking {
            scheduler.sync()

            assertTrue(workManager.getWorkInfosForUniqueWork(WEEKLY_SUMMARY_WORK_NAME).get().isEmpty())
        }

    private fun enqueued(): WorkInfo =
        workManager
            .getWorkInfosForUniqueWork(WEEKLY_SUMMARY_WORK_NAME)
            .get()
            .single { it.state != WorkInfo.State.CANCELLED }

    private companion object {
        /** Wednesday, 2026-03-04, 09:00 UTC. */
        val NOW: Instant = Instant.parse("2026-03-04T09:00:00Z")
    }
}
