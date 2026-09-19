package dev.studyflow.app.timer

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.studyflow.core.database.MissingMigrationPolicy
import dev.studyflow.core.database.StudyFlowDatabase
import dev.studyflow.core.database.StudyFlowDatabaseFactory
import dev.studyflow.core.database.session.OfflineFirstSessionRepository
import dev.studyflow.core.domain.session.SessionCommandResult
import dev.studyflow.core.domain.timer.TimerCommand
import dev.studyflow.core.domain.timer.TimerEngine
import dev.studyflow.core.domain.timer.TimerState
import dev.studyflow.core.model.BootId
import dev.studyflow.core.model.TimeAnchor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class TimerProcessDeathInstrumentedTest {
    @Test
    fun forceStopAndRelaunchReplayTheLogAndRestartTheService() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            context.deleteDatabase(StudyFlowDatabase.NAME)
            seedRunningSession(context)

            closeQuietly(
                instrumentation.uiAutomation.executeShellCommand("am force-stop ${context.packageName}"),
            )
            launchApp(context)
            ContextCompat.startForegroundService(context, TimerForegroundService.refreshIntent(context))

            val database = StudyFlowDatabaseFactory.create(context, MissingMigrationPolicy.DESTRUCTIVE_FOR_DEVELOPMENT)
            try {
                val state = OfflineFirstSessionRepository(database.sessionDao(), DEVICE_ID).activeState()
                assertTrue("expected a running timer after process relaunch, was $state", state is TimerState.Running)
                state as TimerState.Running

                val now = currentAnchor(context)
                val elapsed = TimerEngine.elapsedAt(state, now).counted
                val expected = state.openedAt.wallClockDurationTo(now)
                val drift = (elapsed - expected).absoluteValue
                assertTrue("elapsed should include the forced-stop window", elapsed >= SESSION_AGE)
                assertTrue(
                    "elapsed drift must stay under one second; was ${drift.inWholeMilliseconds}ms",
                    drift < 1.seconds,
                )
            } finally {
                database.close()
            }
        }

    private suspend fun seedRunningSession(context: Context) {
        val database = StudyFlowDatabaseFactory.create(context, MissingMigrationPolicy.DESTRUCTIVE_FOR_DEVELOPMENT)
        try {
            val anchor = currentAnchor(context).minus(SESSION_AGE)
            val result =
                OfflineFirstSessionRepository(database.sessionDao(), DEVICE_ID)
                    .execute(TimerCommand.Start(SESSION_ID), "event-start", anchor)
            assertTrue("seed start failed: $result", result is SessionCommandResult.Applied)
        } finally {
            database.close()
        }
    }

    private fun launchApp(context: Context) {
        val intent =
            requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)) {
                "launcher intent missing for ${context.packageName}"
            }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(500)
    }

    private fun currentAnchor(context: Context): TimeAnchor =
        TimeAnchor(
            uptime = SystemClock.elapsedRealtime().milliseconds,
            wallClock = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            bootId = BootId(Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).toString()),
        )

    private fun TimeAnchor.minus(duration: Duration): TimeAnchor =
        copy(
            uptime = uptime - duration,
            wallClock = wallClock - duration,
        )

    private fun closeQuietly(descriptor: ParcelFileDescriptor) {
        descriptor.close()
        SystemClock.sleep(500)
    }

    private companion object {
        const val DEVICE_ID = "instrumented-device"
        const val SESSION_ID = "instrumented-session"
        val SESSION_AGE = 30.seconds
    }
}
