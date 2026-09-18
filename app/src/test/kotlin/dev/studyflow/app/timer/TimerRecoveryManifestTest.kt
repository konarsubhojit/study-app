package dev.studyflow.app.timer

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerRecoveryManifestTest {
    @Test
    fun `timer recovery receiver is direct boot aware`() {
        val app = RuntimeEnvironment.getApplication()
        val info =
            app.packageManager.getReceiverInfo(
                ComponentName(app, TimerRecoveryReceiver::class.java),
                PackageManager.GET_META_DATA,
            )

        assertTrue(info.directBootAware)
    }

    @Test
    fun `timer recovery receiver handles boot and clock recovery broadcasts`() {
        val app = RuntimeEnvironment.getApplication()
        val component = ComponentName(app, TimerRecoveryReceiver::class.java)
        val actions =
            shadowOf(app.packageManager)
                .getIntentFiltersForReceiver(component)
                .flatMap { filter -> (0 until filter.countActions()).map(filter::getAction) }

        listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        ).forEach { action ->
            assertTrue(
                "Timer recovery receiver not registered for $action",
                action in actions,
            )
        }
    }
}
