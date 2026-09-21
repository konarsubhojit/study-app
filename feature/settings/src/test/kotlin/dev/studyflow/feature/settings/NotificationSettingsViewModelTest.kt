package dev.studyflow.feature.settings

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.studyflow.core.datastore.AlarmRingtoneSettings
import dev.studyflow.core.datastore.WeeklySummarySchedule
import dev.studyflow.core.datastore.WeeklySummarySettings
import dev.studyflow.core.domain.stats.WeeklySummaryScheduling
import dev.studyflow.core.notifications.NotificationChannelStatus
import dev.studyflow.core.notifications.NotificationMessageKey
import dev.studyflow.core.notifications.NotificationPermissionState
import dev.studyflow.core.notifications.NotificationPermissionStatus
import dev.studyflow.core.notifications.NotificationSettingsSnapshot
import dev.studyflow.core.notifications.NotificationSettingsSource
import dev.studyflow.core.notifications.StudyFlowNotificationChannel
import dev.studyflow.core.testing.coroutines.MainDispatcherExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("NotificationSettingsViewModel")
class NotificationSettingsViewModelTest {
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val source = FakeNotificationSettingsSource()
    private val alarmRingtoneSettings = FakeAlarmRingtoneSettings()
    private val weeklySummarySettings = FakeWeeklySummarySettings()
    private val weeklySummaryScheduling = FakeWeeklySummaryScheduling()

    @Test
    fun `the screen reports what the system says, not what the app would like`() =
        runTest(mainDispatcher.dispatcher) {
            source.permission = granted(notificationsEnabled = true)
            source.channels = channels(groupBlocked = setOf(StudyFlowNotificationChannel.UPLOADS))
            batteryDiagnostics.snapshot = batteryDiagnostics.snapshot.copy(batteryOptimised = true)
            val viewModel = viewModel()

            viewModel.onEvent(NotificationSettingsUiEvent.Refresh())
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.loaded)
                assertFalse(state.notificationsBlocked)
                assertNull(state.degradation)
                assertEquals(
                    listOf(StudyFlowNotificationChannel.UPLOADS),
                    state.channels.filterNot(NotificationChannelStatus::enabled).map { it.channel },
                )
                assertTrue(state.batteryDiagnostics.batteryOptimised)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a first refusal is answered with an explanation, then the system dialog`() =
        runTest(mainDispatcher.dispatcher) {
            source.permission =
                NotificationPermissionState(NotificationPermissionStatus.DENIED, notificationsEnabled = false)
            val viewModel = viewModel()
            viewModel.onEvent(NotificationSettingsUiEvent.Refresh(shouldShowRationale = true))
            advanceUntilIdle()
            assertEquals(true, source.lastShouldShowRationale)

            viewModel.effects.test {
                viewModel.onEvent(NotificationSettingsUiEvent.EnableNotifications)
                advanceUntilIdle()
                assertEquals(
                    NotificationMessageKey.RATIONALE_GENERIC,
                    viewModel.state.value.rationale,
                )
                expectNoEvents()

                viewModel.onEvent(NotificationSettingsUiEvent.RationaleAccepted)
                assertEquals(NotificationSettingsUiEffect.RequestPermission, awaitItem())
                advanceUntilIdle()
                assertNull(viewModel.state.value.rationale)
                assertTrue(source.recordedRequest)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `dismissing the explanation says what stops working instead of failing silently`() =
        runTest(mainDispatcher.dispatcher) {
            source.permission =
                NotificationPermissionState(NotificationPermissionStatus.DENIED, notificationsEnabled = false)
            val viewModel = viewModel()
            viewModel.onEvent(NotificationSettingsUiEvent.Refresh())
            viewModel.onEvent(NotificationSettingsUiEvent.EnableNotifications)

            viewModel.onEvent(NotificationSettingsUiEvent.RationaleDismissed)
            advanceUntilIdle()

            assertNull(viewModel.state.value.rationale)
            assertEquals(NotificationMessageKey.DEGRADED_GENERIC, viewModel.state.value.degradation)
        }

    @Test
    fun `a permanent denial sends the user to system settings rather than a dialog that does nothing`() =
        runTest(mainDispatcher.dispatcher) {
            source.permission =
                NotificationPermissionState(
                    NotificationPermissionStatus.PERMANENTLY_DENIED,
                    notificationsEnabled = false,
                )
            val viewModel = viewModel()
            viewModel.onEvent(NotificationSettingsUiEvent.Refresh())
            advanceUntilIdle()

            viewModel.effects.test {
                viewModel.onEvent(NotificationSettingsUiEvent.EnableNotifications)

                val effect = awaitItem() as NotificationSettingsUiEffect.OpenSystemSettings
                advanceUntilIdle()
                assertSame(source.appIntent, effect.intent)
                assertFalse(viewModel.state.value.canRequestPermission)
                assertEquals(NotificationMessageKey.DEGRADED_GENERIC, viewModel.state.value.degradation)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a channel shortcut falls back to the app settings page`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.effects.test {
                viewModel.onEvent(
                    NotificationSettingsUiEvent.OpenChannelSettings(StudyFlowNotificationChannel.ALARMS),
                )

                val effect = awaitItem() as NotificationSettingsUiEffect.OpenSystemSettings
                assertSame(source.channelIntent(StudyFlowNotificationChannel.ALARMS), effect.intent)
                assertSame(source.appIntent, effect.fallbackIntent)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `battery settings shortcut falls back to app settings`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.effects.test {
                viewModel.onEvent(NotificationSettingsUiEvent.OpenBatterySettings)

                val effect = awaitItem() as NotificationSettingsUiEffect.OpenSystemSettings
                assertSame(batteryDiagnostics.batteryIntent, effect.intent)
                assertSame(batteryDiagnostics.appIntent, effect.fallbackIntent)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `granting the permission through the system dialog refreshes the screen`() =
        runTest(mainDispatcher.dispatcher) {
            source.permission =
                NotificationPermissionState(NotificationPermissionStatus.NOT_REQUESTED, notificationsEnabled = false)
            val viewModel = viewModel()
            viewModel.onEvent(NotificationSettingsUiEvent.Refresh())
            advanceUntilIdle()
            assertTrue(viewModel.state.value.notificationsBlocked)

            source.permission = granted(notificationsEnabled = true)
            viewModel.onEvent(NotificationSettingsUiEvent.PermissionResult())
            advanceUntilIdle()

            assertFalse(viewModel.state.value.notificationsBlocked)
            assertNull(viewModel.state.value.degradation)
        }

    @Test
    fun `an open explanation survives process death`() =
        runTest(mainDispatcher.dispatcher) {
            source.permission =
                NotificationPermissionState(NotificationPermissionStatus.DENIED, notificationsEnabled = false)
            val savedState = SavedStateHandle()
            val original = viewModel(savedState)
            original.onEvent(NotificationSettingsUiEvent.Refresh())
            original.onEvent(NotificationSettingsUiEvent.EnableNotifications)
            advanceUntilIdle()

            val restored = viewModel(savedState)
            advanceUntilIdle()

            assertEquals(NotificationMessageKey.RATIONALE_GENERIC, restored.state.value.rationale)
        }

    @Test
    fun `an explanation saved by an older version is dropped rather than crashing on restore`() =
        runTest(mainDispatcher.dispatcher) {
            val savedState =
                SavedStateHandle(mapOf("notificationSettings.rationale" to "RATIONALE_FROM_A_PAST_RELEASE"))

            val viewModel = viewModel(savedState)
            advanceUntilIdle()

            assertNull(viewModel.state.value.rationale)
        }

    @Test
    fun `opting in stores the choice and arms the schedule`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onEvent(NotificationSettingsUiEvent.WeeklySummaryEnabled(enabled = true))
            viewModel.onEvent(
                NotificationSettingsUiEvent.WeeklySummaryTimeChanged(isoDayOfWeek = 1, hour = 7, minute = 30),
            )
            advanceUntilIdle()

            assertEquals(
                WeeklySummarySchedule(enabled = true, isoDayOfWeek = 1, hour = 7, minute = 30),
                viewModel.state.value.weeklySummary,
            )
            assertEquals(2, weeklySummaryScheduling.syncs)
        }

    @Test
    fun `opting out cancels the schedule straight away rather than at the next delivery`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()
            viewModel.onEvent(NotificationSettingsUiEvent.WeeklySummaryEnabled(enabled = true))
            advanceUntilIdle()

            viewModel.onEvent(NotificationSettingsUiEvent.WeeklySummaryEnabled(enabled = false))
            advanceUntilIdle()

            assertFalse(viewModel.state.value.weeklySummary.enabled)
            assertEquals(2, weeklySummaryScheduling.syncs)
        }

    private fun viewModel(savedState: SavedStateHandle = SavedStateHandle()) =
        NotificationSettingsViewModel(
            savedState,
            source,
            alarmRingtoneSettings,
            batteryDiagnostics,
            weeklySummarySettings,
            weeklySummaryScheduling,
        )

    private fun granted(notificationsEnabled: Boolean) =
        NotificationPermissionState(NotificationPermissionStatus.GRANTED, notificationsEnabled)

    private fun channels(groupBlocked: Set<StudyFlowNotificationChannel>) =
        StudyFlowNotificationChannel.entries.map { channel ->
            NotificationChannelStatus(
                channel = channel,
                registered = true,
                importance = channel.importance,
                groupBlocked = channel in groupBlocked,
                appNotificationsEnabled = true,
            )
        }

    private class FakeNotificationSettingsSource : NotificationSettingsSource {
        var permission: NotificationPermissionState =
            NotificationPermissionState(NotificationPermissionStatus.GRANTED, notificationsEnabled = true)
        var channels: List<NotificationChannelStatus> = emptyList()
        var recordedRequest: Boolean = false

        var lastShouldShowRationale: Boolean? = null

        override fun snapshot(shouldShowRationale: Boolean): NotificationSettingsSnapshot {
            lastShouldShowRationale = shouldShowRationale
            return NotificationSettingsSnapshot(permission, channels)
        }

        /**
         * Android types are stubs in a JVM unit test, so the fake hands out identifiable instances
         * and the assertions compare identity rather than reading an `Intent` that is not there.
         */
        val appIntent: Intent = Intent()
        private val channelIntents = StudyFlowNotificationChannel.entries.associateWith { Intent() }

        fun channelIntent(channel: StudyFlowNotificationChannel): Intent = channelIntents.getValue(channel)

        override fun appSettingsIntent(): Intent = appIntent

        override fun channelSettingsIntent(channel: StudyFlowNotificationChannel): Intent = channelIntent(channel)

        override fun recordPermissionRequested() {
            recordedRequest = true
        }
    }

    private class FakeWeeklySummarySettings : WeeklySummarySettings {
        private val backing = MutableStateFlow(WeeklySummarySchedule())
        override val schedule: Flow<WeeklySummarySchedule> = backing

        override suspend fun setEnabled(enabled: Boolean) {
            backing.value = backing.value.copy(enabled = enabled)
        }

        override suspend fun setDeliveryTime(
            isoDayOfWeek: Int,
            hour: Int,
            minute: Int,
        ) {
            backing.value = backing.value.copy(isoDayOfWeek = isoDayOfWeek, hour = hour, minute = minute)
        }
    }

    private class FakeWeeklySummaryScheduling : WeeklySummaryScheduling {
        var syncs: Int = 0

        override suspend fun sync() {
            syncs++
        }
    }

    private class FakeAlarmRingtoneSettings : AlarmRingtoneSettings {
        private val backing = MutableStateFlow("")
        override val uri: Flow<String> = backing

        var lastPersisted: String? = null

        override suspend fun setUri(uri: String) {
            lastPersisted = uri
            backing.value = uri
        }
    }

    private val batteryDiagnostics = FakeBatteryDiagnosticsSource()

    private class FakeBatteryDiagnosticsSource : BatteryDiagnosticsSource {
        var snapshot =
            BatteryDiagnosticsSnapshot(
                batteryOptimised = false,
                standbyBucket = StandbyBucket.ACTIVE,
                manufacturer = "Google",
            )
        val batteryIntent: Intent = Intent()
        val appIntent: Intent = Intent()

        override fun snapshot(): BatteryDiagnosticsSnapshot = snapshot

        override fun batterySettingsIntent(): Intent = batteryIntent

        override fun appSettingsIntent(): Intent = appIntent
    }
}
