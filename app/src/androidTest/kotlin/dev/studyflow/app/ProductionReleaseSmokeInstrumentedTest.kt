package dev.studyflow.app

import android.annotation.SuppressLint
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * Exercises every reflective dependency `app/proguard-rules.pro` protects, end to end, against
 * whatever build R8 actually produced — this only means something when the `Slow verification`
 * workflow runs it as `pixel6Api34ProductionReleaseTestAndroidTest`, the one instrumented task that
 * targets the `productionReleaseTest` build type (`productionRelease` plus `isDebuggable`, see
 * `AndroidApplicationConventionPlugin`). A local `./gradlew test` run of this same class compiles
 * against whichever variant Android Studio picked and proves nothing about R8.
 *
 * A crash anywhere in this walk fails the test directly (Compose's `IdlingResource` propagates it
 * from the composition), but the class of bug this guards — `UninitializedPropertyAccessException`,
 * `ClassNotFoundException`, `NoSuchFieldError` from a stripped or renamed class the platform invokes
 * by name after the crashing frame has already unwound — is exactly the kind logcat still carries
 * even when the in-process assertion above did not catch it (a `BroadcastReceiver` or worker
 * crashing on its own thread, for example), which is why the logcat scan at the end is not
 * redundant with reaching the last screen.
 */
class ProductionReleaseSmokeInstrumentedTest {
    @get:Rule val composeRule: ComposeContentTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesNavigatesAndLeavesNoReflectionCrashInLogcat() {
        // The home screen composes, which already means the Hilt application graph — including
        // every `@HiltWorker` binding and `@EntryPoint` — built successfully.
        composeRule.onNodeWithText("Today").assertIsDisplayed()

        // Settings reads `UserSettingsStore`, the protobuf-lite DataStore path that originally
        // crashed with `RuntimeException: Field theme_ for ea6 not found`.
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Notifications").assertIsDisplayed()

        // The task list is backed by `StudyTaskDao`'s `PagingSource` (Room).
        composeRule.onNodeWithText("Tasks").performClick()
        composeRule.onNodeWithText("Add a task").assertIsDisplayed()

        // History is backed by `SessionDao`'s `PagingSource` (Room).
        composeRule.onNodeWithText("History").performClick()
        composeRule.onNodeWithText("History").assertIsDisplayed()

        // Starting and stopping a session round-trips `TimerForegroundService` and the
        // `SessionEvent` log a `SyncWorker`/`ReminderDeliveryWorker` (`@HiltWorker`, resolved by
        // class name through `HiltWorkerFactory`) later reads.
        composeRule.onNodeWithText("Timer").performClick()
        composeRule.onNodeWithContentDescription("Start study session").performClick()
        composeRule.onNodeWithContentDescription("Stop study session").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop study session").performClick()

        assertNoReflectionCrashInLogcat()
    }

    /**
     * `UiAutomator`'s `logcat -d` reads the whole device buffer since boot rather than only this
     * process's output, so the match is scoped to this app's own package — the managed device
     * image logs plenty of unrelated system-service noise that is not this test's concern.
     *
     * A one-shot read needs nothing `UiAutomation#executeShellCommandRwe` offers.
     */
    @SuppressLint("DiscouragedApi")
    private fun assertNoReflectionCrashInLogcat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val log = device.executeShellCommand("logcat -d -v brief")

        val crashPatterns =
            listOf(
                "UninitializedPropertyAccessException",
                "ClassNotFoundException",
                "NoSuchFieldError",
                "NoSuchMethodError",
            )
        val ownPackageLines = log.lineSequence().filter { context.packageName in it }.toList()

        crashPatterns.forEach { pattern ->
            assertFalse(
                "logcat shows a $pattern for ${context.packageName} — a reflective dependency is " +
                    "missing an R8 keep rule:\n${ownPackageLines.filter { pattern in it }.joinToString("\n")}",
                ownPackageLines.any { pattern in it },
            )
        }
    }
}
